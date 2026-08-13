package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.ui.common.BaselineSection
import co.ke.kumea.ui.common.LocationCaptureSection

private data class WaterOption(val key: String, val labelResId: Int)

private val waterOptions = listOf(
    WaterOption("dam", R.string.water_dam),
    WaterOption("rain", R.string.water_rain),
    WaterOption("borehole", R.string.water_borehole),
)

/**
 * Self-registration: a farmer adding their own shamba.
 *
 * Field order is KWAP-03 §5.2 and it is not arbitrary — the person comes first,
 * because until this change the farmer's own flow captured no name and no phone
 * at all while the officer's flow captured both. A farm with no person attached
 * is not a register entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmCreateScreen(
    onBack: () -> Unit,
    viewModel: FarmDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_farm)) },
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
            // ① The person.
            OutlinedTextField(
                value = state.farmerName,
                onValueChange = viewModel::onFarmerNameChange,
                label = { Text(stringResource(R.string.farmer_name)) },
                placeholder = { Text(stringResource(R.string.farmer_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = state.farmerPhone,
                onValueChange = viewModel::onFarmerPhoneChange,
                label = { Text(stringResource(R.string.farmer_phone)) },
                placeholder = { Text(stringResource(R.string.farmer_phone_hint)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
            )

            // ② The place.
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.farm_name)) },
                placeholder = { Text(stringResource(R.string.farm_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ③ What grows on it — and what the farmer would try.
            CropMultiSelect(
                selection = state.crops,
                onCropCycle = viewModel::onCropCycle,
            )

            OutlinedTextField(
                value = state.acres,
                onValueChange = viewModel::onAcresChange,
                label = { Text(stringResource(R.string.farm_size)) },
                placeholder = { Text(stringResource(R.string.farm_size_hint)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )

            Text(stringResource(R.string.water_source), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                waterOptions.forEach { water ->
                    FilterChip(
                        selected = state.waterSource == water.key,
                        onClick = { viewModel.onWaterSourceChange(water.key) },
                        label = { Text(stringResource(water.labelResId)) },
                    )
                }
            }

            // ④ Where it is — real coordinates or nothing at all.
            HorizontalDivider()
            LocationCaptureSection(controller = viewModel.location)

            // ⑤ The counterfactual. Prompted, skippable, never blocking.
            HorizontalDivider()
            BaselineSection(
                input = state.baseline,
                onQtyChange = viewModel::onBaselineQtyChange,
                onUnitChange = viewModel::onBaselineUnitChange,
                onBagSizeChange = viewModel::onBaselineBagSizeChange,
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { viewModel.saveFarm(onSuccess = onBack) },
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
                    Text(stringResource(R.string.save_farm))
                }
            }
        }
    }
}

