package co.ke.kumea.ui.screen.field

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.ConversionSource
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.data.local.ReplantIntent
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.HarvestRepository
import co.ke.kumea.data.repository.PlantingRepository
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
// SANITY sits immediately after QUANTITY (§2.8): the cross-check has to happen
// while the farmer still has the figure they just typed in mind.
enum class HarvestStep { UNIT, QUANTITY, SANITY, SPLIT, REPLANT, REVIEW }

/**
 * Which branch the farmer took at the yield sanity line (§2.8).
 *
 * Recorded rather than discarded, because "a farmer looked at 450 kg/acre and
 * said yes" is itself a data point — an outlier that was confirmed in person is
 * a different thing from an outlier nobody was ever shown.
 */
object YieldCheck {
    /** No planting record, so no per-acre figure could be derived. Not shown. */
    const val NOT_SHOWN = "not_shown"
    const val CONFIRMED = "confirmed"
    const val REVISED = "revised"
}

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
    /** True when re-opened on an existing record; save updates instead of creating. */
    val isEdit: Boolean = false,
    /** Planted area (centi-acres) from the farm's planting record; null if none. */
    val plantedAreaCenti: Long? = null,
    /** [YieldCheck] — which branch the farmer took, or NOT_SHOWN. */
    val yieldCheck: String = YieldCheck.NOT_SHOWN,
    /** Only ever true on the edit path, while the existing record is read. */
    val loading: Boolean = false,
) {
    /** Bags cannot move on until a size is chosen; nothing else asks. */
    val unitStepComplete: Boolean
        get() = unit != null && (unit != HarvestUnits.BAGS || bagSizeCenti != null)

    /**
     * kg-per-unit to apply, and where it came from. Null only when a bag size
     * is still outstanding — which the UNIT step will not let past.
     */
    /** Canonical kilograms for the typed quantity, or null while incomplete. */
    val qtyKgCenti: Long?
        get() {
            val quantity = Quantity.parseToCenti(quantityText) ?: return null
            val factor = conversion?.first ?: return null
            return YieldConversion.toKgCenti(quantity, factor)
        }

    /**
     * kg per acre × 100, or null when there is no planting record to divide by
     * — §2.8 then shows the total only, and the wizard skips the SANITY step
     * rather than showing a line it cannot compute.
     */
    val kgPerAcreCenti: Long?
        get() {
            val kg = qtyKgCenti ?: return null
            val area = plantedAreaCenti ?: return null
            return YieldConversion.kgPerAcreCenti(kg, area)
        }

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
    private val fieldRepository: FieldRepository,
    private val plantingRepository: PlantingRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val fieldId: String = checkNotNull(savedStateHandle["fieldId"]) {
        "HarvestWizardViewModel requires a fieldId nav argument"
    }

    /**
     * Set when the farmer tapped the season record to correct it (§2.1). Null on
     * the create path. This is the whole of "edit mode" — there is no second
     * screen, because the wizard already asks every question the record holds.
     */
    private val harvestId: String? = savedStateHandle["harvestId"]

    private val _state = MutableStateFlow(
        HarvestWizardState(isEdit = harvestId != null, loading = harvestId != null),
    )
    val state: StateFlow<HarvestWizardState> = _state.asStateFlow()

    init {
        // The planted area for the sanity line (§2.8). The wizard is entered
        // with a fieldId, and plantings are farm-level, so the farm is resolved
        // through the field. Absent planting → null → the SANITY step is skipped
        // and the harvest still saves normally.
        viewModelScope.launch {
            val farmId = fieldRepository.getById(fieldId)?.farmId
            val planting = farmId?.let { plantingRepository.getLatestForFarm(it) }
            _state.update {
                it.copy(plantedAreaCenti = planting?.plantedAreaCenti?.takeIf { area -> area > 0 })
            }
        }

        val id = harvestId
        if (id != null) {
            viewModelScope.launch {
                val existing = harvestRepository.getById(id)
                if (existing == null) {
                    // Deleted underneath us, or a stale back-stack entry. Better
                    // to say so than to silently open an empty wizard that would
                    // save a SECOND harvest.
                    _state.update {
                        it.copy(loading = false, error = "That harvest record is no longer there")
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        unit = existing.unit,
                        // The stored factor IS the bag size the farmer stated;
                        // re-deriving it from a default table would overwrite an
                        // answer with an assumption.
                        bagSizeCenti = existing.conversionFactorCenti
                            .takeIf { _ -> existing.unit == HarvestUnits.BAGS },
                        quantityText = Quantity.formatCenti(existing.quantityCenti),
                        keptText = existing.keptCenti?.let(Quantity::formatCenti).orEmpty(),
                        soldText = existing.soldCenti?.let(Quantity::formatCenti).orEmpty(),
                        replantIntent = existing.replantIntent,
                        replantMonth = existing.replantMonth,
                    )
                }
            }
        }
    }

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
                // Skip the sanity line entirely when there is no planted area to
                // divide by (§2.8). Showing "720 kg — does that sound right?"
                // with no per-acre figure asks the farmer to check arithmetic
                // that was never done.
                _state.update {
                    if (it.kgPerAcreCenti != null) it.copy(step = HarvestStep.SANITY)
                    else it.copy(step = HarvestStep.SPLIT, yieldCheck = YieldCheck.NOT_SHOWN)
                }
            }
            HarvestStep.SANITY -> _state.update {
                it.copy(step = HarvestStep.SPLIT, yieldCheck = YieldCheck.CONFIRMED)
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

    /**
     * "Change" — back to the quantity, marked REVISED. It does NOT silently
     * adjust anything (§2.8); the farmer retypes the figure themselves.
     */
    fun reviseQuantity() = _state.update {
        it.copy(step = HarvestStep.QUANTITY, yieldCheck = YieldCheck.REVISED, error = null)
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
            HarvestStep.SANITY -> HarvestStep.QUANTITY
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
        val editingId = harvestId
        viewModelScope.launch {
            try {
                // Converted HERE, at entry, while the farmer is standing in
                // front of us and can still be asked. Not in a script in
                // December, when there is nobody left to ask.
                val qtyKgCenti = YieldConversion.toKgCenti(quantity, factorCenti)
                val keptCenti = s.keptText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti)
                val soldCenti = s.soldText.takeIf { it.isNotBlank() }?.let(Quantity::parseToCenti)
                if (editingId != null) {
                    // Upsert by id. harvestDate is deliberately NOT rewritten —
                    // correcting a quantity does not move when the harvest
                    // happened, and the server's PATCH body has no date either.
                    harvestRepository.updateLocal(
                        id = editingId,
                        quantityCenti = quantity,
                        unit = unit,
                        qtyKgCenti = qtyKgCenti,
                        conversionFactorCenti = factorCenti,
                        conversionSource = conversionSource,
                        keptCenti = keptCenti,
                        soldCenti = soldCenti,
                        replantIntent = intent,
                        replantMonth = s.replantMonth,
                    )
                } else {
                    harvestRepository.createLocal(
                        fieldId = fieldId,
                        harvestDate = Clock.System.now().toString(),
                        quantityCenti = quantity,
                        unit = unit,
                        qtyKgCenti = qtyKgCenti,
                        conversionFactorCenti = factorCenti,
                        conversionSource = conversionSource,
                        keptCenti = keptCenti,
                        soldCenti = soldCenti,
                        replantIntent = intent,
                        replantMonth = s.replantMonth,
                    )
                }
                _state.update { it.copy(isSaving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Failed to save harvest") }
            }
        }
    }
}
