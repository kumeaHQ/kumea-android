package co.ke.kumea.ui.screen.field

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.ConversionSource
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.data.local.ReplantIntent
import co.ke.kumea.data.repository.HarvestRepository
import co.ke.kumea.util.Quantity
import co.ke.kumea.util.YieldConversion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

// Unit BEFORE quantity (field feedback, 11 Jul): "bags — how many?" matches the
// farmer's mental model; "how many of what?" does not. Ordinal drives progress.
enum class HarvestStep { UNIT, QUANTITY, SPLIT, REPLANT, REVIEW }

data class HarvestWizardState(
    val step: HarvestStep = HarvestStep.UNIT,
    val quantityText: String = "",
    val unit: String? = null,
    /**
     * kg-per-bag × 100, asked inline on the UNIT step when the farmer picks
     * bags. Null for every other unit, which has a standard the table can
     * supply — see [co.ke.kumea.util.YieldConversion].
     */
    val bagSizeCenti: Long? = null,
    val keptText: String = "",
    val soldText: String = "",
    val replantIntent: String? = null,
    val replantMonth: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    /** Bags cannot move on until a size is chosen; nothing else asks. */
    val unitStepComplete: Boolean
        get() = unit != null && (unit != HarvestUnits.BAGS || bagSizeCenti != null)

    /**
     * kg-per-unit to apply, and where it came from. Null only when a bag size
     * is still outstanding — which the UNIT step will not let past.
     */
    val conversion: Pair<Long, String>?
        get() {
            val unit = unit ?: return null
            return if (unit == HarvestUnits.BAGS) {
                bagSizeCenti?.let { it to ConversionSource.USER_STATED }
            } else {
                YieldConversion.defaultFactorCenti(unit)?.let {
                    // kg is an identity conversion — the farmer stated
                    // kilograms, so nothing was assumed on their behalf.
                    val source = if (unit == HarvestUnits.KG) ConversionSource.USER_STATED
                    else ConversionSource.DEFAULT_TABLE
                    it to source
                }
            }
        }
}

/**
 * Build-2 T3 state machine. All quantity handling via Quantity (centi Long,
 * integer math) — never float. ONE atomic createLocal at save; abandoning the
 * wizard writes nothing.
 */
@HiltViewModel
class HarvestWizardViewModel @Inject constructor(
    private val harvestRepository: HarvestRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val fieldId: String = checkNotNull(savedStateHandle["fieldId"]) {
        "HarvestWizardViewModel requires a fieldId nav argument"
    }

    private val _state = MutableStateFlow(HarvestWizardState())
    val state: StateFlow<HarvestWizardState> = _state.asStateFlow()

    fun onQuantityChange(text: String) = _state.update { it.copy(quantityText = text, error = null) }
    fun onUnitSelected(unit: String) = _state.update {
        // Changing away from bags drops the size with it. A 90 kg factor left
        // sitting behind a gorogoro would be a silent 45× error.
        it.copy(unit = unit, bagSizeCenti = null, error = null)
    }

    fun onBagSizeSelected(centi: Long) = _state.update { it.copy(bagSizeCenti = centi, error = null) }
    fun onKeptChange(text: String) = _state.update { it.copy(keptText = text, error = null) }
    fun onSoldChange(text: String) = _state.update { it.copy(soldText = text, error = null) }

    fun onReplantSelected(intent: String) = _state.update {
        // Choosing NO completes the step immediately; YES waits for a month.
        if (intent == ReplantIntent.NO) {
            it.copy(replantIntent = intent, replantMonth = null, step = HarvestStep.REVIEW, error = null)
        } else {
            it.copy(replantIntent = intent, error = null)
        }
    }

    fun onReplantMonthSelected(yearMonth: String) = _state.update { it.copy(replantMonth = yearMonth) }

    /** Next 6 months from the current month, as "YYYY-MM". Integer date math. */
    fun upcomingMonths(): List<String> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return (0..5).map { offset ->
            val zeroBased = today.monthNumber - 1 + offset
            val year = today.year + zeroBased / 12
            val month = zeroBased % 12 + 1
            "%04d-%02d".format(year, month)
        }
    }

    fun stepNext() {
        val s = _state.value
        when (s.step) {
            HarvestStep.UNIT -> {
                // "What size bag?" is asked here rather than as its own step:
                // it is part of choosing the unit, and one extra tap in the
                // farmer's own flow is what makes every bag figure in the
                // dataset comparable (KWAP-03 §4.4).
                if (!s.unitStepComplete) return
                _state.update { it.copy(step = HarvestStep.QUANTITY) }
            }
            HarvestStep.QUANTITY -> {
                val q = Quantity.parseToCenti(s.quantityText)
                if (q == null || q <= 0) {
                    _state.update { it.copy(error = "Enter a valid amount (e.g. 1.2)") }
                    return
                }
                _state.update { it.copy(step = HarvestStep.SPLIT) }
            }
            HarvestStep.SPLIT -> {
                // Optional step, but if values are typed they must parse and
                // must not exceed the total — validated, never coerced.
                val quantity = Quantity.parseToCenti(s.quantityText) ?: return
                val kept = s.keptText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti)
                val sold = s.soldText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti)
                if (s.keptText.isNotBlank() && kept == null) {
                    _state.update { it.copy(error = "Enter a valid kept amount") }
                    return
                }
                if (s.soldText.isNotBlank() && sold == null) {
                    _state.update { it.copy(error = "Enter a valid sold amount") }
                    return
                }
                if ((kept ?: 0) + (sold ?: 0) > quantity) {
                    _state.update { it.copy(error = "Kept + sold is more than the harvest") }
                    return
                }
                _state.update { it.copy(step = HarvestStep.REPLANT) }
            }
            HarvestStep.REPLANT -> {
                if (s.replantIntent == ReplantIntent.YES && s.replantMonth == null) return
                _state.update { it.copy(step = HarvestStep.REVIEW) }
            }
            HarvestStep.REVIEW -> Unit
        }
    }

    fun skipSplit() = _state.update {
        it.copy(keptText = "", soldText = "", step = HarvestStep.REPLANT, error = null)
    }

    /** @return true if the wizard consumed the back press (stepped back). */
    fun stepBack(): Boolean {
        val s = _state.value
        val previous = when (s.step) {
            HarvestStep.UNIT -> return false
            HarvestStep.QUANTITY -> HarvestStep.UNIT
            HarvestStep.SPLIT -> HarvestStep.QUANTITY
            HarvestStep.REPLANT -> HarvestStep.SPLIT
            HarvestStep.REVIEW -> HarvestStep.REPLANT
        }
        _state.update { it.copy(step = previous, error = null) }
        return true
    }

    fun save() {
        val s = _state.value
        if (s.isSaving) return
        val quantity = Quantity.parseToCenti(s.quantityText)
        val unit = s.unit
        val intent = s.replantIntent
        val conversion = s.conversion
        if (quantity == null || quantity <= 0 || unit == null || intent == null || conversion == null) {
            _state.update { it.copy(error = "Something is missing — go back and check") }
            return
        }
        val (factorCenti, conversionSource) = conversion
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                harvestRepository.createLocal(
                    fieldId = fieldId,
                    harvestDate = Clock.System.now().toString(),
                    quantityCenti = quantity,
                    unit = unit,
                    // Converted HERE, at entry, while the farmer is standing in
                    // front of us and can still be asked. Not in a script in
                    // December, when there is nobody left to ask.
                    qtyKgCenti = YieldConversion.toKgCenti(quantity, factorCenti),
                    conversionFactorCenti = factorCenti,
                    conversionSource = conversionSource,
                    keptCenti = s.keptText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti),
                    soldCenti = s.soldText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti),
                    replantIntent = intent,
                    replantMonth = s.replantMonth,
                )
                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Failed to save harvest") }
            }
        }
    }
}
