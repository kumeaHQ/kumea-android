package co.ke.kumea.ui.screen.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class PinSetupUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    // One-shot: tells the screen to clear both PIN fields after a mismatch.
    val clearFields: Boolean = false,
    val navigateToFarms: Boolean = false,
)

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val registrationToken: String =
        savedStateHandle.get<String>("registrationToken").orEmpty()

    private val _state = MutableStateFlow(PinSetupUiState())
    val state: StateFlow<PinSetupUiState> = _state.asStateFlow()

    fun onConfirm(pin: String, confirmPin: String) {
        if (_state.value.isLoading) return
        if (!pin.matches(PIN_REGEX)) {
            _state.update { it.copy(error = "PIN must be 4-6 digits.") }
            return
        }
        if (pin != confirmPin) {
            _state.update { it.copy(error = "PINs don't match. Try again.", clearFields = true) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.register(registrationToken, pin)
                _state.update { it.copy(isLoading = false, navigateToFarms = true) }
            } catch (e: IOException) {
                _state.update { it.copy(isLoading = false, error = "No connection. Try again.") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Couldn't create your account. Try again.") }
            }
        }
    }

    fun onFieldsCleared() {
        _state.update { it.copy(clearFields = false) }
    }

    fun onNavigated() {
        _state.update { it.copy(navigateToFarms = false) }
    }

    private companion object {
        val PIN_REGEX = Regex("^\\d{4,6}$")
    }
}
