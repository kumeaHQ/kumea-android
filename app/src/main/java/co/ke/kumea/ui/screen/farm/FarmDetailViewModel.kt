package co.ke.kumea.ui.screen.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.FarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FarmUiState(
    val name: String = "",
    val lat: String = "",
    val lng: String = "",
    val waterSource: String = "",
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FarmDetailViewModel @Inject constructor(
    private val repository: FarmRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FarmUiState())
    val uiState: StateFlow<FarmUiState> = _uiState.asStateFlow()

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun onLatChange(lat: String) {
        _uiState.update { it.copy(lat = lat) }
    }

    fun onLngChange(lng: String) {
        _uiState.update { it.copy(lng = lng) }
    }

    fun onWaterSourceChange(source: String) {
        _uiState.update { it.copy(waterSource = source) }
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
                repository.createLocal(
                    name = state.name,
                    locationLat = state.lat.toDoubleOrNull(),
                    locationLng = state.lng.toDoubleOrNull(),
                    waterSource = state.waterSource.ifBlank { null }
                )
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save farm") }
            }
        }
    }
}
