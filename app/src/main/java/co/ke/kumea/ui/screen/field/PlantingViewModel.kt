package co.ke.kumea.ui.screen.field

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.CropStatus
import co.ke.kumea.data.local.TrialRole
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.PlantingRepository
import co.ke.kumea.domain.model.Crops
import co.ke.kumea.util.Area
import co.ke.kumea.util.Money
import co.ke.kumea.util.Quantity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

/**
 * The guided series (§2.4). DATE first, then five questions in the order the
 * ticket fixes them — crop, planted area, seed weight, variety, seed cost.
 *
 * "Planting again next season?" is NOT here (decision 2): it was speculative and
 * unanswerable at sowing time, and nobody could act on the answer.
 */
enum class PlantingStep { DATE, CROP, AREA, SEED_KG, VARIETY, SEED_COST, REVIEW }

data class PlantingUiState(
    val step: PlantingStep = PlantingStep.DATE,
    val loading: Boolean = true,
    /** Opens on today and cannot go past it — see [PlantingViewModel.maxSelectableDate]. */
    val plantedOn: LocalDate? = null,
    val crop: String? = null,
    /** The farm's `growing` crops, for the one-tap case. May be empty — see [cropOptions]. */
    val cropOptions: List<String> = emptyList(),
    /** True when no crop could be sourced and the full catalogue is offered instead. */
    val cropFromCatalogue: Boolean = false,
    val areaText: String = "",
    val seedKgText: String = "",
    val variety: String = "",
    val seedCostText: String = "",
    val trialRole: String = TrialRole.NONE,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val areaCenti: Long? get() = Area.parseToCenti(areaText)
    val seedKgCenti: Long? get() = Quantity.parseToCenti(seedKgText)
    val seedCostCents: Long? get() = seedCostText.takeIf { it.isNotBlank() }?.let(Money::parseToCents)
}

@HiltViewModel
class PlantingViewModel @Inject constructor(
    private val plantingRepository: PlantingRepository,
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * FARM-level. Planting was a column on Field; it is an entity on Farm now
     * (§2.3), and §2.2 removes Field from the farmer's vocabulary — so the route
     * carries the farm and the single Field is auto-resolved below.
     */
    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "PlantingViewModel requires a farmId nav argument"
    }

    /** Only for the linked seed Purchase — `NoteEntity.fieldId` is still a Field. */
    private var fieldId: String? = null

    private val _state = MutableStateFlow(PlantingUiState())
    val state: StateFlow<PlantingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val farm = farmRepository.getById(farmId)
            fieldId = fieldRepository.getActiveByFarm(farmId).first().firstOrNull()?.id

            // ── WHICH CROPS TO OFFER (§2.4 q1, and the empty case RB flagged) ──
            //
            // farm_crops first: it is the farm's own answer to "what do you
            // grow", captured by KWAP-03's multi-select. On the handset it is
            // EMPTY — TestFarm predates that screen — so this is the live path,
            // not a hypothetical.
            //
            // Then `farms.cropType`, the denorm that has existed since v9 and
            // does hold 'beans' for TestFarm.
            //
            // Then the whole catalogue. Never "unknown": `crop` is required
            // (§2.3), a variety is meaningless without it, and a literal
            // "unknown" would be indistinguishable at analysis time from a real
            // answer. Making the farmer pick is the honest fallback.
            val growing = farmRepository.getCropsOnce(farmId)
                .filter { it.status == CropStatus.GROWING }
                .map { it.crop }
            val fallback = farm?.cropType?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            val options = growing.ifEmpty { fallback }

            _state.update {
                it.copy(
                    loading = false,
                    plantedOn = today(),
                    cropOptions = options.ifEmpty {
                        Crops.GROUPS.flatMap { group -> group.crops }.map { crop -> crop.key }
                    },
                    cropFromCatalogue = options.isEmpty(),
                    // One growing crop → pre-selected, so q1 is a confirmation
                    // rather than a question (§2.4: "one tap if there's only one").
                    crop = options.singleOrNull(),
                    // Pre-filled from the farm's acres, editable. THE one
                    // Double→centi crossing lives in Area, not here.
                    areaText = farm?.acres
                        ?.let { acres -> Area.formatCenti(Area.fromAcresDouble(acres)) }
                        .orEmpty(),
                )
            }
        }
    }

    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /**
     * A planting date cannot be in the future. The three-option list (Today /
     * Yesterday / Pick another day) made that impossible by construction; a bare
     * calendar does not, so the cap is explicit. Decision 2 removed the only
     * question that was ever about next season.
     */
    fun maxSelectableDate(): LocalDate = today()

    fun onDateSelected(date: LocalDate) = _state.update {
        if (date > maxSelectableDate()) {
            it.copy(error = "A planting date cannot be in the future")
        } else {
            it.copy(plantedOn = date, error = null)
        }
    }

    fun onCropSelected(crop: String) = _state.update { it.copy(crop = crop, error = null) }
    fun onAreaChange(text: String) = _state.update { it.copy(areaText = text, error = null) }
    fun onSeedKgChange(text: String) = _state.update { it.copy(seedKgText = text, error = null) }
    fun onVarietyChange(text: String) = _state.update { it.copy(variety = text) }
    fun onSeedCostChange(text: String) = _state.update { it.copy(seedCostText = text, error = null) }
    fun onTrialRoleSelected(role: String) = _state.update { it.copy(trialRole = role) }

    fun stepNext() {
        val s = _state.value
        when (s.step) {
            PlantingStep.DATE -> {
                val date = s.plantedOn ?: return
                if (date > maxSelectableDate()) {
                    _state.update { it.copy(error = "A planting date cannot be in the future") }
                    return
                }
                _state.update { it.copy(step = PlantingStep.CROP) }
            }
            PlantingStep.CROP -> {
                if (s.crop.isNullOrBlank()) {
                    _state.update { it.copy(error = "Choose what you planted") }
                    return
                }
                _state.update { it.copy(step = PlantingStep.AREA) }
            }
            PlantingStep.AREA -> {
                val area = s.areaCenti
                if (area == null || area <= 0) {
                    _state.update { it.copy(error = "Enter how much you planted (e.g. 1.5)") }
                    return
                }
                _state.update { it.copy(step = PlantingStep.SEED_KG) }
            }
            PlantingStep.SEED_KG -> {
                val kg = s.seedKgCenti
                if (kg == null || kg <= 0) {
                    _state.update { it.copy(error = "Enter the seed weight in kg (e.g. 12)") }
                    return
                }
                _state.update { it.copy(step = PlantingStep.VARIETY) }
            }
            // Both skippable (§2.4) — `stepNext` and `skip` differ only in
            // whether the typed value is kept.
            PlantingStep.VARIETY -> _state.update { it.copy(step = PlantingStep.SEED_COST) }
            PlantingStep.SEED_COST -> {
                if (s.seedCostText.isNotBlank() && s.seedCostCents == null) {
                    _state.update { it.copy(error = "Enter a valid amount, e.g. 2000") }
                    return
                }
                _state.update { it.copy(step = PlantingStep.REVIEW) }
            }
            PlantingStep.REVIEW -> Unit
        }
    }

    fun skipVariety() = _state.update { it.copy(variety = "", step = PlantingStep.SEED_COST) }

    fun skipSeedCost() = _state.update {
        it.copy(seedCostText = "", step = PlantingStep.REVIEW, error = null)
    }

    /** @return true if the flow consumed the back press. */
    fun stepBack(): Boolean {
        val previous = when (_state.value.step) {
            PlantingStep.DATE -> return false
            PlantingStep.CROP -> PlantingStep.DATE
            PlantingStep.AREA -> PlantingStep.CROP
            PlantingStep.SEED_KG -> PlantingStep.AREA
            PlantingStep.VARIETY -> PlantingStep.SEED_KG
            PlantingStep.SEED_COST -> PlantingStep.VARIETY
            PlantingStep.REVIEW -> PlantingStep.SEED_COST
        }
        _state.update { it.copy(step = previous, error = null) }
        return true
    }

    fun save(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.isSaving) return
        val date = s.plantedOn
        val crop = s.crop
        val area = s.areaCenti
        val seedKg = s.seedKgCenti
        val field = fieldId
        if (date == null || crop.isNullOrBlank() || area == null || seedKg == null) {
            _state.update { it.copy(error = "Something is missing — go back and check") }
            return
        }
        if (field == null) {
            // Only reachable if the farm has no Field at all, which
            // FarmDetailViewModel's auto-create makes impossible for farms
            // created in-app. Surfaced rather than silently dropping the cost.
            _state.update { it.copy(error = "This farm has no record to attach to — pull to refresh") }
            return
        }
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                plantingRepository.createLocal(
                    farmId = farmId,
                    fieldId = field,
                    plantedOn = date.toString(),
                    crop = crop,
                    seedVariety = s.variety.takeIf { it.isNotBlank() },
                    seedKgCenti = seedKg,
                    plantedAreaCenti = area,
                    // Null when skipped. §2.5 writes the linked Purchase only
                    // when this is non-null — a stated 0 is a different answer.
                    seedCostCents = s.seedCostCents,
                    trialRole = s.trialRole,
                )
                _state.update { it.copy(isSaving = false, saved = true) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = e.message ?: "Failed to save planting") }
            }
        }
    }
}
