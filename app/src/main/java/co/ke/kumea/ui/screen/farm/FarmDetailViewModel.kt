package co.ke.kumea.ui.screen.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.location.LocationCapturer
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.domain.model.BaselineInput
import co.ke.kumea.domain.model.CropSelection
import co.ke.kumea.ui.common.LocationCaptureController
import co.ke.kumea.util.normalizeKenyanPhone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FarmUiState(
    /** THE PERSON. Required — a register entry with no name is not one. */
    val farmerName: String = "",
    /** THE PERSON's number. Optional here; required on the on-behalf path. */
    val farmerPhone: String = "",
    /** THE PLACE. Build-1 locked these as two different things. */
    val name: String = "",
    val crops: CropSelection = CropSelection(),
    val acres: String = "",
    val waterSource: String? = null,
    val baseline: BaselineInput = BaselineInput(),
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FarmDetailViewModel @Inject constructor(
    private val repository: FarmRepository,
    private val fieldRepository: FieldRepository,
    locationCapturer: LocationCapturer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FarmUiState())
    val uiState: StateFlow<FarmUiState> = _uiState.asStateFlow()

    /**
     * Location lives in its own state machine rather than in [FarmUiState]:
     * it is the only part of this form that is asynchronous, cancellable and
     * permission-gated, and folding sixty seconds of streaming accuracy into the
     * same object as two text fields is how the two get tangled.
     */
    val location = LocationCaptureController(locationCapturer, viewModelScope)

    fun onFarmerNameChange(value: String) = _uiState.update { it.copy(farmerName = value) }

    fun onFarmerPhoneChange(value: String) = _uiState.update { it.copy(farmerPhone = value) }

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name) }

    /** Tap a crop: not growing → growing → interested → not growing. */
    fun onCropCycle(crop: String) = _uiState.update { state ->
        val crops = state.crops
        val next = when (crop) {
            in crops.growing -> CropSelection(
                growing = crops.growing - crop,
                interested = crops.interested + crop,
            )
            in crops.interested -> CropSelection(
                growing = crops.growing,
                interested = crops.interested - crop,
            )
            else -> CropSelection(
                growing = crops.growing + crop,
                interested = crops.interested - crop,
            )
        }
        state.copy(crops = next)
    }

    fun onAcresChange(acres: String) = _uiState.update { it.copy(acres = acres) }

    fun onWaterSourceChange(source: String) = _uiState.update {
        it.copy(waterSource = if (it.waterSource == source) null else source)
    }

    fun onBaselineQtyChange(value: String) = _uiState.update {
        it.copy(baseline = it.baseline.copy(qty = value))
    }

    fun onBaselineUnitChange(unit: String) = _uiState.update { state ->
        // Clearing the bag size on every unit change is not tidiness: carrying
        // one across is how a gorogoro ends up weighing 90 kg.
        val next = if (state.baseline.unit == unit) {
            state.baseline.copy(unit = null, bagSizeCenti = null)
        } else {
            state.baseline.copy(unit = unit, bagSizeCenti = null)
        }
        state.copy(baseline = next)
    }

    fun onBaselineBagSizeChange(centi: Long) = _uiState.update {
        it.copy(baseline = it.baseline.copy(bagSizeCenti = centi))
    }

    fun saveFarm(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.farmerName.isBlank()) {
            _uiState.update { it.copy(error = "Farmer name cannot be empty") }
            return
        }
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "Shamba name cannot be empty") }
            return
        }
        // Normalised at the gate, where a human can still fix the typo — the
        // server validates loosely on purpose (every rejection there is a 400,
        // and the client retries 400 for ever).
        val phone = state.farmerPhone.takeIf { it.isNotBlank() }?.let(::normalizeKenyanPhone)
        if (state.farmerPhone.isNotBlank() && phone == null) {
            _uiState.update { it.copy(error = "Phone must be a Kenyan number, e.g. 0712 345 678") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val farmId = repository.createLocal(
                    name = state.name,
                    acres = state.acres.toDoubleOrNull(),
                    // Null unless the farmer explicitly kept a fix. There is no
                    // longer a flag that can claim otherwise.
                    location = location.captured(),
                    waterSource = state.waterSource,
                    farmerName = state.farmerName.trim(),
                    farmerPhone = phone,
                    crops = state.crops,
                    baseline = state.baseline.toBaseline(fallbackCrop = state.crops.primaryGrowing),
                )
                // Auto-create a field so notes have somewhere to attach — and so
                // crop and acreage reach the server, which carries them on the
                // Field and not on the Farm.
                fieldRepository.createLocal(
                    farmId = farmId,
                    name = state.name,
                    acres = state.acres,
                    cropType = state.crops.primaryGrowing,
                )
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save farm") }
            }
        }
    }
}
