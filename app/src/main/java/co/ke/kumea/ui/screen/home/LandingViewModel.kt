package co.ke.kumea.ui.screen.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.PersonaRepository
import co.ke.kumea.data.repository.PersonaResult
import co.ke.kumea.domain.model.Persona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LandingState {
    data object Loading : LandingState
    data class Ready(val persona: Persona) : LandingState
    data class Error(val message: String) : LandingState
}

/**
 * Resolves the persona once after login/startup, then the screen routes to the
 * matching home (P1-T7). The single persona-resolution point — no manual role
 * selection. A hard failure surfaces for retry rather than guessing a persona.
 */
@HiltViewModel
class LandingViewModel @Inject constructor(
    private val personaRepository: PersonaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<LandingState>(LandingState.Loading)
    val state: StateFlow<LandingState> = _state.asStateFlow()

    init {
        resolve()
    }

    fun resolve() {
        viewModelScope.launch {
            _state.value = LandingState.Loading
            _state.value = try {
                when (val result = personaRepository.resolve()) {
                    is PersonaResult.Resolved -> LandingState.Ready(result.persona)
                    is PersonaResult.Failed -> LandingState.Error(result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Landing", "persona resolve failed", e)
                LandingState.Error(e.message ?: "Couldn't load your account. Try again.")
            }
        }
    }
}
