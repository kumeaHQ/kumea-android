package co.ke.kumea.ui.screen.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FarmUiState(
    val name: String = "",
    val cropType: String? = null,
    val acres: String = "",
    val waterSource: String? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val useGps: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FarmDetailViewModel @Inject constructor(
    private val repository: FarmRepository,
    private val fieldRepository: FieldRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FarmUiState())
    val uiState: StateFlow<FarmUiState> = _uiState.asStateFlow()

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun onCropChange(crop: String) {
        _uiState.update { it.copy(cropType = if (it.cropType == crop) null else crop) }
    }

    fun onAcresChange(acres: String) {
        _uiState.update { it.copy(acres = acres) }
    }

    fun onWaterSourceChange(source: String) {
        _uiState.update { it.copy(waterSource = if (it.waterSource == source) null else source) }
    }

    fun requestLocation() {
        // TODO: integrate FusedLocationProvider for real GPS
        // For now, set a flag; real coords will come from location callback
        _uiState.update { it.copy(useGps = true) }
    }

    fun saveFarm(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "Name cannot be empty") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val farmId = repository.createLocal(
                    name = state.name,
                    cropType = state.cropType,
                    acres = state.acres.toDoubleOrNull(),
                    locationLat = state.locationLat,
                    locationLng = state.locationLng,
                    useGps = state.useGps,
                    waterSource = state.waterSource,
                )
                // Auto-create a field so notes have somewhere to attach
                fieldRepository.createLocal(
                    farmId = farmId,
                    name = state.name,
                    acres = state.acres,
                    cropType = state.cropType,
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
