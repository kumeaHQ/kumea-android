package co.ke.kumea.ui.screen.ping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.remote.dto.HealthResponse
import co.ke.kumea.data.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

sealed interface PingState {
    data object Idle : PingState
    data object Loading : PingState
    data class Result(val response: HealthResponse) : PingState
    data class Error(val message: String) : PingState
}

@HiltViewModel
class PingViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PingState>(PingState.Idle)
    val state: StateFlow<PingState> = _state.asStateFlow()

    fun ping() {
        // Don't fire a second request while one is in flight.
        if (_state.value is PingState.Loading) return

        _state.value = PingState.Loading
        viewModelScope.launch {
            _state.value = try {
                PingState.Result(healthRepository.ping())
            } catch (e: IOException) {
                // Network failure (no connection, timeout, DNS, TLS, etc.)
                PingState.Error(e.message ?: "Network error. Check your connection.")
            } catch (e: Exception) {
                // HTTP errors, deserialization failures, anything else
                PingState.Error(e.message ?: "Something went wrong.")
            }
        }
    }
}
