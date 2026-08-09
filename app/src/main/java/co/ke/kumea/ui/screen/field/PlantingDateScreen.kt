@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package co.ke.kumea.ui.screen.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.repository.FieldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

data class PlantingDateUiState(
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

/**
 * Build-2 T2: one question, one save. Writes Field.plantedAt offline-first via
 * FieldRepository.setPlantedAt (works on synced rows too).
 */
@HiltViewModel
class PlantingDateViewModel @Inject constructor(
    private val fieldRepository: FieldRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val fieldId: String = checkNotNull(savedStateHandle["fieldId"]) {
        "PlantingDateViewModel requires a fieldId nav argument"
    }

    private val _state = MutableStateFlow(PlantingDateUiState())
    val state: StateFlow<PlantingDateUiState> = _state.asStateFlow()

    /** Local date (device zone) for today / yesterday quick choices. */
    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    fun yesterday(): LocalDate = today().minus(1, DateTimeUnit.DAY)

    fun save(date: LocalDate) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                // Wire format: full ISO instant at UTC midnight — Prisma
                // DateTime-safe, date-precision by convention (display takes 10).
                fieldRepository.setPlantedAt(fieldId, "${date}T00:00:00.000Z")
                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Failed to save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantingDateScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: PlantingDateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.planting_title)) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.planting_question),
                style = MaterialTheme.typography.headlineSmall,
            )

            ChoiceCard(
                label = stringResource(R.string.planting_today),
                selected = selected == viewModel.today(),
                onClick = { selected = viewModel.today() },
            )
            ChoiceCard(
                label = stringResource(R.string.planting_yesterday),
                selected = selected == viewModel.yesterday(),
                onClick = { selected = viewModel.yesterday() },
            )
            ChoiceCard(
                label = selected
                    ?.takeIf { it != viewModel.today() && it != viewModel.yesterday() }
                    ?.toString()
                    ?: stringResource(R.string.planting_pick_day),
                selected = selected != null && selected != viewModel.today() && selected != viewModel.yesterday(),
                onClick = { showDatePicker = true },
            )

            Text(
                text = stringResource(R.string.planting_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { selected?.let(viewModel::save) },
                enabled = !state.isSaving && selected != null,
                shape = co.ke.kumea.ui.theme.KumeaButtonShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.planting_save))
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selected = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC).date
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** Interactive selection wears Leaf Wash + ✓ + weight — never color alone. */
@Composable
private fun ChoiceCard(label: String, selected: Boolean, onClick: () -> Unit) {
    co.ke.kumea.ui.common.PaperCard(
        onClick = onClick,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) {
                    androidx.compose.ui.text.font.FontWeight.SemiBold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Normal
                },
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
