package co.ke.kumea.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StartupState {
    data object Loading : StartupState
    data class Ready(val startDestination: String) : StartupState
}

/**
 * Decides the nav start destination on cold start:
 *  - valid saved session (GET /auth/me 200) -> FarmList
 *  - no/invalid token                        -> PhoneEntry (token cleared)
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val destination = if (authRepository.isAuthenticated()) {
                Routes.FARM_LIST
            } else {
                Routes.PHONE_ENTRY
            }
            _state.value = StartupState.Ready(destination)
        }
    }
}
