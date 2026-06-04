package co.ke.kumea.ui.screen.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.NoteRepository
import co.ke.kumea.util.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject

data class FieldOption(val id: String, val name: String)

data class NoteFormState(
    val fields: List<FieldOption> = emptyList(),
    val selectedFieldId: String? = null,
    val type: NoteType = NoteType.ACTIVITY,
    val body: String = "",
    val amount: String = "",
    val costCategory: CostCategory? = null,
    val occurredAtMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** ACTIVITY may omit a cost; PURCHASE and SALE must carry an amount. */
    val amountRequired: Boolean get() = type != NoteType.ACTIVITY

    /**
     * Cost categories only make sense on a cost (PURCHASE / costed ACTIVITY) —
     * a SALE is revenue. The selector is hidden for a SALE; the rollup ignores a
     * category on a SALE anyway, but we don't even let one be entered.
     */
    val showCategory: Boolean get() = type != NoteType.SALE

    /** Live, float-free preview of what the typed amount parses to (or null). */
    val parsedPreview: Long? get() = if (amount.isBlank()) null else Money.parseToCents(amount)
}

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    fieldRepository: FieldRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "NoteDetailViewModel requires a farmId nav argument"
    }

    private val _uiState = MutableStateFlow(NoteFormState())
    val uiState: StateFlow<NoteFormState> = _uiState.asStateFlow()

    init {
        // Notes attach to a Field; load this farm's fields for the picker and
        // default to the first (the auto-created "Main field").
        viewModelScope.launch {
            fieldRepository.getActiveByFarm(farmId).collect { fields ->
                val options = fields.map { FieldOption(it.id, it.name) }
                _uiState.update { state ->
                    state.copy(
                        fields = options,
                        selectedFieldId = state.selectedFieldId ?: options.firstOrNull()?.id,
                    )
                }
            }
        }
    }

    fun onFieldSelected(fieldId: String) = _uiState.update { it.copy(selectedFieldId = fieldId) }
    fun onTypeChange(type: NoteType) = _uiState.update {
        // Switching to a SALE clears any chosen cost category — a sale has no cost.
        if (type == NoteType.SALE) it.copy(type = type, costCategory = null)
        else it.copy(type = type)
    }
    fun onBodyChange(body: String) = _uiState.update { it.copy(body = body) }
    fun onAmountChange(amount: String) = _uiState.update { it.copy(amount = amount) }
    /** Toggle a category chip — tapping the selected one clears it (optional field). */
    fun onCategoryChange(category: CostCategory?) =
        _uiState.update { it.copy(costCategory = if (it.costCategory == category) null else category) }
    fun onDateSelected(millis: Long) = _uiState.update { it.copy(occurredAtMillis = millis) }

    fun saveNote(onSuccess: () -> Unit) {
        val state = _uiState.value

        if (state.body.isBlank()) {
            _uiState.update { it.copy(error = "Note text cannot be empty") }
            return
        }
        val fieldId = state.selectedFieldId
        if (fieldId == null) {
            _uiState.update { it.copy(error = "No field available — pull to refresh on the farm first") }
            return
        }

        // Money: integer-only parse (never Double). Required for PURCHASE/SALE,
        // optional for ACTIVITY. A non-blank but unparseable amount is an error.
        val amountCents: Long?
        if (state.amount.isBlank()) {
            if (state.amountRequired) {
                _uiState.update { it.copy(error = "${state.type.label()} needs an amount") }
                return
            }
            amountCents = null
        } else {
            val parsed = Money.parseToCents(state.amount)
            if (parsed == null) {
                _uiState.update { it.copy(error = "Enter a valid amount (e.g. 2000 or 2000.50)") }
                return
            }
            amountCents = parsed
        }

        val occurredAt = Instant.fromEpochMilliseconds(state.occurredAtMillis).toString()

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                noteRepository.createLocal(
                    fieldId = fieldId,
                    type = state.type,
                    body = state.body.trim(),
                    amountCents = amountCents,
                    occurredAt = occurredAt,
                    // Optional cost label; null for a SALE or when left unset.
                    costCategory = state.costCategory,
                )
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save note") }
            }
        }
    }
}

fun NoteType.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

fun CostCategory.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
