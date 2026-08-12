package co.ke.kumea.ui.screen.officer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.domain.model.Crops

/**
 * Register a farmer — the officer's and village agent's create screen
 * (KWAP-01 step 4). Deliberately NOT a reuse of `FarmCreateScreen`: that screen
 * asks a farmer about their own shamba ("What you call the place"), and this one
 * asks a WAO about a person she is entering into a register. Same table, two
 * genuinely different questions.
 *
 * ZERO COMMERCIAL SURFACE, by construction rather than by hiding: there is no
 * price, order, SKU or earnings composable anywhere in this file, and its
 * ViewModel has no money type at all (KWAP-01 §5 — officers never touch money).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegisterFarmerScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RegisterFarmerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Register a farmer") },
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
            // Ward: derived from the signed-in agent, shown so the WAO can see
            // the scope she is registering into. Read-only on purpose — she
            // cannot express another ward, so there is nothing to choose.
            WardNotice(ward = state.ward, loaded = state.identityLoaded)

            OutlinedTextField(
                value = state.farmerName,
                onValueChange = viewModel::onFarmerNameChange,
                label = { Text("Farmer's name") },
                placeholder = { Text("e.g. Sila Serem") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.farmerPhone,
                onValueChange = viewModel::onFarmerPhoneChange,
                label = { Text("Phone (optional)") },
                placeholder = { Text("0712 345 678") },
                supportingText = { Text("Not verified — nobody sends this number a code.") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.shambaName,
                onValueChange = viewModel::onShambaNameChange,
                label = { Text("Shamba name (optional)") },
                placeholder = { Text("What the place is called — e.g. Sigona") },
                supportingText = { Text("Leave blank and we'll use the farmer's name.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Single-select, legumes only. Kumea N is a rhizobia inoculant — it
            // does nothing for maize — so a register entry reading "maize" is a
            // row the research can't use. The grouped multi-select with an
            // "interested in growing" state is Batch A and needs a column that
            // holds a set; `fields.crop_type` holds one string. See Crops.
            Text("Main legume", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Crops.LEGUMES.forEach { crop ->
                    FilterChip(
                        selected = state.cropType == crop.key,
                        onClick = { viewModel.onCropChange(crop.key) },
                        label = { Text(crop.label) },
                    )
                }
            }

            OutlinedTextField(
                value = state.acres,
                onValueChange = viewModel::onAcresChange,
                label = { Text("Size (acres, optional)") },
                placeholder = { Text("e.g. 0.5") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                    Text("Save farmer")
                }
            }
        }
    }
}

@Composable
private fun WardNotice(ward: String?, loaded: Boolean) {
    if (!loaded) return
    val text = if (ward.isNullOrBlank()) {
        // Honest, and not a blocker: refusing to register would punish a WAO for
        // an admin gap on her own agent record. But the ward of a registration
        // is recovered through that record, so without one this row joins the
        // register with no ward at all — worth saying, plainly.
        "No ward set on your profile — this registration won't carry one. Ask Kumea to set your ward."
    } else {
        "Registering in $ward · from your profile"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (ward.isNullOrBlank()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
