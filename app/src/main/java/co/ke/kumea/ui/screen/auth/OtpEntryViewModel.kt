package co.ke.kumea.ui.screen.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

sealed interface OtpNav {
    data class ToPinSetup(val registrationToken: String) : OtpNav
    data class ToPinEntry(val phone: String) : OtpNav
}

data class OtpEntryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val resendSecondsRemaining: Int = 0,
    val nav: OtpNav? = null,
)

@HiltViewModel
class OtpEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val phone: String = savedStateHandle.get<String>("phone").orEmpty()

    private val _state = MutableStateFlow(OtpEntryUiState())
    val state: StateFlow<OtpEntryUiState> = _state.asStateFlow()

    init {
        // OTP was just sent from PhoneEntry, so start the resend cooldown immediately.
        startResendCountdown()
    }

    fun onVerify(code: String) {
        if (_state.value.isLoading) return
        if (code.length != 6) {
            _state.update { it.copy(error = "Enter the 6-digit code.") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val res = authRepository.verifyOtp(phone, code)
                val nav = if (res.isNewUser) {
                    OtpNav.ToPinSetup(res.registrationToken)
                } else {
                    OtpNav.ToPinEntry(phone)
                }
                _state.update { it.copy(isLoading = false, nav = nav) }
            } catch (e: IOException) {
                _state.update { it.copy(isLoading = false, error = "No connection. Try again.") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Wrong or expired code. Try again.") }
            }
        }
    }

    fun onResend() {
        if (_state.value.resendSecondsRemaining > 0) return
        viewModelScope.launch {
            try {
                authRepository.sendOtp(phone)
                startResendCountdown()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't resend the code. Try again.") }
            }
        }
    }

    private fun startResendCountdown() {
        viewModelScope.launch {
            for (s in RESEND_COOLDOWN_SECONDS downTo 0) {
                _state.update { it.copy(resendSecondsRemaining = s) }
                if (s > 0) delay(1000)
            }
        }
    }

    fun onNavigated() {
        _state.update { it.copy(nav = null) }
    }

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 30
    }
}
