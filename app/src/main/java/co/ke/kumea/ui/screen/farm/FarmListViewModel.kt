package co.ke.kumea.ui.screen.farm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
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
    private val fieldRepository: FieldRepository,
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.pushPending()
                Log.d("Sync", "✅ pushPending farms OK")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pushPending farms: ${e.message}", e)
                _errorMessage.value = "Farm push failed: ${e.message}"
            }
            try {
                repository.pullSince()
                Log.d("Sync", "✅ pullSince farms OK")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pullSince farms: ${e.message}", e)
                if (_errorMessage.value == null) _errorMessage.value = "Farm pull failed: ${e.message}"
            }
            try {
                fieldRepository.pushPending()
                Log.d("Sync", "✅ pushPending fields OK")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pushPending fields: ${e.message}", e)
            }
            try {
                fieldRepository.pullSince()
                Log.d("Sync", "✅ pullSince fields OK – fields now in Room")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pullSince fields: ${e.message}", e)
                if (_errorMessage.value == null) _errorMessage.value = "Field pull failed: ${e.message}"
            }
            _isRefreshing.value = false
        }
    }

    fun onErrorShown() {
        _errorMessage.value = null
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
