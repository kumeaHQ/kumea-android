@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package co.ke.kumea.ui.screen.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.TrialRole
import co.ke.kumea.domain.model.Crops
import co.ke.kumea.ui.theme.KumeaButtonShape
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * THE PLANTING FLOW (KWAP-03-V2 §2.4). One question per screen, in the fixed
 * order, writing ONE [co.ke.kumea.data.local.PlantingEntity] at the end —
 * abandoning it writes nothing, same discipline as the harvest wizard.
 *
 * Replaces the Build-2 three-option date screen (Today / Yesterday / Pick
 * another day), which is decision 1. The two things that list was genuinely good
 * at are preserved as calendar CONSTRAINTS rather than as buttons: it opens on
 * today, so "planted today" is still two taps, and it cannot select tomorrow.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlantingScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: PlantingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.planting_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.stepBack()) onBack() }) {
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            when (state.step) {
                PlantingStep.DATE -> DateStep(viewModel, state)
                PlantingStep.CROP -> CropStep(viewModel, state)
                PlantingStep.AREA -> AreaStep(viewModel, state)
                PlantingStep.SEED_KG -> SeedKgStep(viewModel, state)
                PlantingStep.VARIETY -> VarietyStep(viewModel, state)
                PlantingStep.SEED_COST -> SeedCostStep(viewModel, state)
                PlantingStep.REVIEW -> ReviewStep(viewModel, state, onDone)
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StepQuestion(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun NextButton(onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.next)) }
}

/**
 * A single calendar, open on today, capped at today.
 *
 * The cap is enforced twice on purpose: [SelectableDates] greys out future days
 * so the farmer never taps one, and the ViewModel re-checks on advance, because
 * a UI constraint is not a data rule. A planting date in the future would put a
 * season's yield-per-acre on a crop that is not in the ground.
 */
@Composable
private fun DateStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    val maxDate = remember { viewModel.maxSelectableDate() }
    val maxMillis = remember(maxDate) {
        maxDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = maxMillis,
        selectableDates = remember(maxMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMillis
                override fun isSelectableYear(year: Int) = year <= maxDate.year
            }
        },
    )

    StepQuestion(stringResource(R.string.planting_question))
    DatePicker(state = datePickerState, showModeToggle = false)

    val selectedDate = datePickerState.selectedDateMillis?.let {
        Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
    }
    NextButton(
        onClick = {
            selectedDate?.let(viewModel::onDateSelected)
            viewModel.stepNext()
        },
        enabled = selectedDate != null,
    )
}

/** §2.4 q1. One tap when the farm grows one thing; the catalogue when it grows none. */
@Composable
private fun CropStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    StepQuestion(stringResource(R.string.planting_which_crop))
    if (state.cropFromCatalogue) {
        Text(
            stringResource(R.string.planting_crop_unknown_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.cropOptions.forEach { crop ->
            FilterChip(
                selected = state.crop == crop,
                onClick = { viewModel.onCropSelected(crop) },
                label = { Text(Crops.label(crop) ?: crop) },
            )
        }
    }
    NextButton(onClick = viewModel::stepNext, enabled = !state.crop.isNullOrBlank())
}

/**
 * §2.4 q2, and the wording is load-bearing.
 *
 * "How much of your shamba did you plant?" — NOT "on what size of land?", which
 * would capture farm size a second time and break decision 6. Planted area is a
 * different fact, and it is the denominator the impact report needs: yield per
 * acre must divide by what was planted, or a farmer who sowed half their shamba
 * reads as having got half the yield.
 */
@Composable
private fun AreaStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    StepQuestion(stringResource(R.string.planting_area_question))
    Text(
        stringResource(R.string.planting_area_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.areaText,
        onValueChange = viewModel::onAreaChange,
        label = { Text(stringResource(R.string.planting_area_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NextButton(onClick = viewModel::stepNext)
}

@Composable
private fun SeedKgStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    StepQuestion(stringResource(R.string.planting_seed_kg_question))
    OutlinedTextField(
        value = state.seedKgText,
        onValueChange = viewModel::onSeedKgChange,
        label = { Text(stringResource(R.string.planting_seed_kg_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NextButton(onClick = viewModel::stepNext)
}

/** Skippable. Free text this season — no per-crop variety list exists yet (§7.3). */
@Composable
private fun VarietyStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    StepQuestion(stringResource(R.string.planting_variety_question))
    OutlinedTextField(
        value = state.variety,
        onValueChange = viewModel::onVarietyChange,
        label = { Text(stringResource(R.string.planting_variety_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    NextButton(onClick = viewModel::stepNext)
    TextButton(onClick = viewModel::skipVariety, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.skip))
    }
}

/**
 * Skippable. §2.5: a value here also writes a PURCHASE note linked back to this
 * planting, so the farmer is told now rather than discovering a duplicate later.
 */
@Composable
private fun SeedCostStep(viewModel: PlantingViewModel, state: PlantingUiState) {
    StepQuestion(stringResource(R.string.planting_seed_cost_question))
    OutlinedTextField(
        value = state.seedCostText,
        onValueChange = viewModel::onSeedCostChange,
        label = { Text(stringResource(R.string.planting_seed_cost_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.seedCostText.isNotBlank()) {
        Text(
            stringResource(R.string.planting_seed_cost_linked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    NextButton(onClick = viewModel::stepNext)
    TextButton(onClick = viewModel::skipSeedCost, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.skip))
    }
}

@Composable
private fun ReviewStep(viewModel: PlantingViewModel, state: PlantingUiState, onDone: () -> Unit) {
    StepQuestion(stringResource(R.string.planting_review_title))

    ReviewRow(stringResource(R.string.planting_review_date), state.plantedOn?.toString().orEmpty())
    ReviewRow(
        stringResource(R.string.planting_review_crop),
        state.crop?.let { Crops.label(it) ?: it }.orEmpty(),
    )
    ReviewRow(
        stringResource(R.string.planting_review_area),
        stringResource(R.string.acres_value, state.areaText),
    )
    ReviewRow(
        stringResource(R.string.planting_review_seed),
        stringResource(R.string.kg_value, state.seedKgText),
    )
    if (state.variety.isNotBlank()) {
        ReviewRow(stringResource(R.string.planting_review_variety), state.variety)
    }
    if (state.seedCostText.isNotBlank()) {
        ReviewRow(
            stringResource(R.string.planting_review_seed_cost),
            stringResource(R.string.kes_value, state.seedCostText),
        )
    }

    // The split-plot arm (decision 8). Lives on the planting because a control
    // plot is a property of what was sown this season, not of the land.
    Text(
        stringResource(R.string.planting_trial_role),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TrialRole.NONE to R.string.trial_none,
            TrialRole.TREATED to R.string.trial_treated,
            TrialRole.CONTROL to R.string.trial_control,
        ).forEach { (role, labelRes) ->
            FilterChip(
                selected = state.trialRole == role,
                onClick = { viewModel.onTrialRoleSelected(role) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    Button(
        onClick = { viewModel.save(onDone) },
        enabled = !state.isSaving,
        shape = KumeaButtonShape,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text(stringResource(R.string.planting_save)) }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
