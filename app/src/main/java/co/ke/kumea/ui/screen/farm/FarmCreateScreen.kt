package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R

private data class CropOption(val key: String, val labelResId: Int)
private data class WaterOption(val key: String, val labelResId: Int)

private val cropOptions = listOf(
    CropOption("beans", R.string.crop_beans),
    CropOption("maize", R.string.crop_maize),
    CropOption("soya", R.string.crop_soya),
)
private val waterOptions = listOf(
    WaterOption("dam", R.string.water_dam),
    WaterOption("rain", R.string.water_rain),
    WaterOption("borehole", R.string.water_borehole),
)

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
            // Farm name
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.farm_name)) },
                placeholder = { Text(stringResource(R.string.farm_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Crop picker
            Text(stringResource(R.string.crop_type), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cropOptions.forEach { crop ->
                    val selected = state.cropType == crop.key
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onCropChange(crop.key) },
                        label = { Text(stringResource(crop.labelResId)) },
                    )
                }
            }

            // Size in acres
            OutlinedTextField(
                value = state.acres,
                onValueChange = viewModel::onAcresChange,
                label = { Text(stringResource(R.string.farm_size)) },
                placeholder = { Text(stringResource(R.string.farm_size_hint)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )

            // Water source taps
            Text(stringResource(R.string.water_source), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                waterOptions.forEach { water ->
                    val selected = state.waterSource == water.key
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onWaterSourceChange(water.key) },
                        label = { Text(stringResource(water.labelResId)) },
                    )
                }
            }

            // Location button (replaces Lat/Lng fields)
            OutlinedButton(
                onClick = { viewModel.requestLocation() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.useGps) stringResource(R.string.location_set)
                    else stringResource(R.string.use_location)
                )
            }

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
