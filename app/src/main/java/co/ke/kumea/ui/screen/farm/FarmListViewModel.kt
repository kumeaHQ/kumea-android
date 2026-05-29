package co.ke.kumea.ui.screen.farm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.FarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FarmListViewModel @Inject constructor(
    private val repository: FarmRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val farms: StateFlow<List<FarmEntity>> = repository.getAllActive()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            // Push local pending edits up first, then pull server deltas. Both are
            // best-effort — a sync failure shouldn't crash pull-to-refresh.
            try {
                repository.pushPending()
            } catch (e: Exception) {
                // ignore; WorkManager still owns durable retry
            }
            try {
                repository.pullSince()
            } catch (e: Exception) {
                // ignore; offline or transient
            }
            _isRefreshing.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _loggedOut.value = true
        }
    }

    fun onLoggedOutHandled() {
        _loggedOut.value = false
    }
}
