package co.ke.kumea.ui.screen.auth

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

data class PhoneEntryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    // Non-null => navigate to OtpEntry with this normalised phone, then call onNavigated().
    val navigateToOtp: String? = null,
)

@HiltViewModel
class PhoneEntryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhoneEntryUiState())
    val state: StateFlow<PhoneEntryUiState> = _state.asStateFlow()

    fun onContinue(rawPhone: String) {
        if (_state.value.isLoading) return
        val phone = normalizeKenyanPhone(rawPhone)
        if (phone == null) {
            _state.update { it.copy(error = "Enter a valid Kenyan number, e.g. 0712 345 678") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                // send-otp never reveals user existence; we always go to OtpEntry next.
                authRepository.sendOtp(phone)
                _state.update { it.copy(isLoading = false, navigateToOtp = phone) }
            } catch (e: IOException) {
                _state.update { it.copy(isLoading = false, error = "No connection. Check your network and try again.") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Couldn't send the code. Please try again.") }
            }
        }
    }

    fun onNavigated() {
        _state.update { it.copy(navigateToOtp = null) }
    }
}

/**
 * Normalise a Kenyan mobile number to +2547xxxxxxxx / +2541xxxxxxxx form.
 * Returns null if the input isn't a valid Safaricom/Airtel-style number.
 */
internal fun normalizeKenyanPhone(input: String): String? {
    val digits = input.trim().filter { it.isDigit() || it == '+' }
    val normalized = when {
        digits.startsWith("+254") -> digits
        digits.startsWith("254") -> "+$digits"
        digits.startsWith("0") -> "+254" + digits.drop(1)
        digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "+254$digits"
        else -> digits
    }
    return if (Regex("^\\+254[17]\\d{8}$").matches(normalized)) normalized else null
}
