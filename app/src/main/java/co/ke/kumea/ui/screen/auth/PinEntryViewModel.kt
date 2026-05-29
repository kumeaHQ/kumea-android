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

data class PinEntryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    // One-shot: tells the screen to clear the PIN field after a wrong PIN.
    val clearField: Boolean = false,
    val navigateToFarms: Boolean = false,
)

@HiltViewModel
class PinEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val phone: String = savedStateHandle.get<String>("phone").orEmpty()

    private val _state = MutableStateFlow(PinEntryUiState())
    val state: StateFlow<PinEntryUiState> = _state.asStateFlow()

    fun onSubmit(pin: String) {
        if (_state.value.isLoading) return
        if (!pin.matches(PIN_REGEX)) {
            _state.update { it.copy(error = "PIN must be 4-6 digits.") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.login(phone, pin)
                _state.update { it.copy(isLoading = false, navigateToFarms = true) }
            } catch (e: IOException) {
                _state.update { it.copy(isLoading = false, error = "No connection. Try again.") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Wrong PIN. Try again.", clearField = true) }
            }
        }
    }

    fun onFieldCleared() {
        _state.update { it.copy(clearField = false) }
    }

    fun onNavigated() {
        _state.update { it.copy(navigateToFarms = false) }
    }

    private companion object {
        val PIN_REGEX = Regex("^\\d{4,6}$")
    }
}
