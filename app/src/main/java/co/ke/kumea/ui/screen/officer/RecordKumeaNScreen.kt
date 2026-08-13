package co.ke.kumea.ui.screen.officer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R

/**
 * Record a Kumea N handover (KWAP-03 §7).
 *
 * FOUR FIELDS AND NO FIFTH. Strain, pack size, batch, quantity — roughly the
 * effort of the free-text note this replaces, and enough that the KWAP-02
 * backfill can map `(strainCode, packSizeG, batchNumber) → batchId` with a
 * script. There is no price field and there must never be one: this cohort is
 * given the product.
 *
 * The recording agent is derived from the caller, never picked from a list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordKumeaNScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RecordKumeaNViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_kumea_n)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.kumea_n_strain), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RecordKumeaNViewModel.STRAINS.forEach { crop ->
                    FilterChip(
                        selected = state.strainCode == crop.key,
                        onClick = { viewModel.onStrainChange(crop.key) },
                        label = { Text(crop.label) },
                    )
                }
            }

            Text(stringResource(R.string.kumea_n_pack_size), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordKumeaNViewModel.PACK_SIZES_G.forEach { grams ->
                    FilterChip(
                        selected = state.packSizeG == grams,
                        onClick = { viewModel.onPackSizeChange(grams) },
                        label = { Text(stringResource(R.string.kumea_n_pack_size_g, grams)) },
                    )
                }
            }

            // REQUIRED. Without it the row cannot be reconciled at season end,
            // which is the entire reason this is a table and not a note.
            OutlinedTextField(
                value = state.batchNumber,
                onValueChange = viewModel::onBatchChange,
                label = { Text(stringResource(R.string.kumea_n_batch)) },
                placeholder = { Text(stringResource(R.string.kumea_n_batch_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.qty,
                onValueChange = viewModel::onQtyChange,
                label = { Text(stringResource(R.string.kumea_n_qty)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { viewModel.save(onSaved = onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.kumea_n_save))
                }
            }
        }
    }
}
