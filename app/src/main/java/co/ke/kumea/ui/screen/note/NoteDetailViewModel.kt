package co.ke.kumea.ui.screen.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.NoteRepository
import co.ke.kumea.util.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject

data class FieldOption(val id: String, val name: String)

/**
 * The purchase picklist (Build-3 §5): what a farmer buys, as icon cards.
 *
 * EVERY ITEM MAPS TO A CATEGORY THE SERVER ALREADY ACCEPTS. Herbicide has
 * always written the wire's SPRAY; Kumea N now writes OTHER for the same
 * reason. It previously wrote a client-invented `BIOFIX` that the server's enum
 * never gained, so every Kumea N purchase was rejected with a validation 400 and
 * retried for ever — 400 is not terminal client-side. Fixed in v12; the stranded
 * rows are rewritten by MIGRATION_11_12.
 *
 * OTHER, not SEED: an inoculant is not seed, and folding it into seed spend
 * would quietly inflate the one cost line KWAP is trying to measure. OTHER loses
 * only the label, and the label is [labelRes]'s job anyway. When RB adds a real
 * server value, change this one mapping — and not before.
 */
enum class PurchaseItem(val category: CostCategory) {
    KUMEA_N_SACHET(CostCategory.OTHER),
    SEED(CostCategory.SEED),
    FERTILISER(CostCategory.FERTILISER),
    HERBICIDE(CostCategory.SPRAY),
    LABOUR(CostCategory.LABOUR),
    TRANSPORT(CostCategory.TRANSPORT),
    OTHER(CostCategory.OTHER),
}

/**
 * WHICH FORM THIS IS (KWAP-03-V2 §2.6 / §2.7).
 *
 * The single "Add record" screen with a three-way type chip is split in two,
 * because it was asking a farmer to classify before it asked them anything they
 * knew. The two ledgers are money; an observation is not a ledger entry and now
 * has its own entry point.
 */
enum class NoteMode {
    /** The two ledgers: PURCHASE (money out, −, red) and SALE (money in, +, green). */
    MONEY,

    /**
     * An observation. No amount field exists in this mode at all — nodulation,
     * vigour, a WAO's visit. This is where the mid-season research evidence
     * lives, and it is the thing the impact report leans on to show Kumea N was
     * working DURING the season rather than only at harvest.
     */
    OBSERVATION,
}

data class NoteFormState(
    val mode: NoteMode = NoteMode.MONEY,
    val fields: List<FieldOption> = emptyList(),
    val selectedFieldId: String? = null,
    val type: NoteType = NoteType.PURCHASE,
    val body: String = "",
    val amount: String = "",
    val purchaseItem: PurchaseItem? = null,
    val occurredAtMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** The two money types, in ledger order. Never includes ACTIVITY (§2.6). */
    val moneyTypes: List<NoteType> get() = listOf(NoteType.PURCHASE, NoteType.SALE)

    /** Money always carries an amount now; an observation has no amount field. */
    val amountRequired: Boolean get() = mode == NoteMode.MONEY

    /** Live, float-free preview of what the typed amount parses to (or null). */
    val parsedPreview: Long? get() = if (amount.isBlank()) null else Money.parseToCents(amount)
}

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val fieldRepository: FieldRepository,
    private val farmRepository: FarmRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "NoteDetailViewModel requires a farmId nav argument"
    }

    /** "observation" opens the no-money form; anything else is the two ledgers. */
    private val mode: NoteMode =
        if (savedStateHandle.get<String>("mode") == MODE_OBSERVATION) NoteMode.OBSERVATION
        else NoteMode.MONEY

    private val _uiState = MutableStateFlow(
        NoteFormState(
            mode = mode,
            type = if (mode == NoteMode.OBSERVATION) NoteType.ACTIVITY else NoteType.PURCHASE,
        )
    )
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
        // ACTIVITY is unreachable from the money form and the only type in the
        // observation form, so a type change is always PURCHASE ↔ SALE.
        if (it.mode == NoteMode.OBSERVATION) it
        // Leaving PURCHASE clears the picked item — only a purchase carries one.
        else if (type == NoteType.PURCHASE) it.copy(type = type, error = null)
        else it.copy(type = type, purchaseItem = null, error = null)
    }
    fun onBodyChange(body: String) = _uiState.update { it.copy(body = body) }
    fun onAmountChange(amount: String) = _uiState.update { it.copy(amount = amount) }
    fun onPurchaseItemSelected(item: PurchaseItem) =
        _uiState.update { it.copy(purchaseItem = item, error = null) }
    fun onDateSelected(millis: Long) = _uiState.update { it.copy(occurredAtMillis = millis) }

    /**
     * @param purchaseFallbackBody localized label of the picked purchase item —
     * used as the note body when the optional free text is left empty (§5:
     * the picklist is the input; free text is just a note).
     */
    fun saveNote(purchaseFallbackBody: String? = null, onSuccess: () -> Unit) {
        val state = _uiState.value

        if (state.type == NoteType.PURCHASE && state.purchaseItem == null) {
            _uiState.update { it.copy(error = "Choose what you bought") }
            return
        }
        val body = state.body.ifBlank {
            if (state.type == NoteType.PURCHASE) purchaseFallbackBody.orEmpty() else ""
        }
        if (body.isBlank()) {
            _uiState.update { it.copy(error = "Note text cannot be empty") }
            return
        }
        // Money: integer-only parse (never Double). Required for PURCHASE/SALE,
        // optional for ACTIVITY. A non-blank but unparseable amount is an error.
        val amountCents: Long?
        if (state.mode == NoteMode.OBSERVATION) {
            // §2.7: an observation carries no money, so there is nothing to
            // parse. NoteRepository.createLocal enforces the same rule — the
            // form is not the only caller, and a rule kept only in a composable
            // is a rule one screen keeps.
            amountCents = null
        } else if (state.amount.isBlank()) {
            _uiState.update { it.copy(error = "${state.type.label()} needs an amount") }
            return
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
                // Notes attach to a Field. Use the selected field if there is one;
                // otherwise lazily create this farm's "Main field" so a note can
                // ALWAYS be saved — this covers a server-pulled farm, an old farm
                // from before farm-create auto-created a field, and the case where
                // that auto-create didn't land. Offline-first: a local Room insert,
                // pushed before the note via the SyncableRepository FK order.
                val fieldId = state.selectedFieldId ?: run {
                    // The lazily-created Main field mirrors the farm's own
                    // size/crop (Sigona 0.5 acre reads 0.5, not a hardcoded 1.0).
                    val farm = farmRepository.getById(farmId)
                    fieldRepository.createLocal(
                        farmId = farmId,
                        name = "Main field",
                        acres = farm?.acres?.toString() ?: "1.0",
                        cropType = farm?.cropType,
                    )
                }
                noteRepository.createLocal(
                    fieldId = fieldId,
                    type = state.type,
                    body = body.trim(),
                    amountCents = amountCents,
                    occurredAt = occurredAt,
                    // The picked purchase item labels the cost; null otherwise.
                    costCategory = state.purchaseItem?.category,
                )
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save note") }
            }
        }
    }
}

fun NoteType.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Nav-arg value selecting the observation form. */
const val MODE_OBSERVATION = "observation"
