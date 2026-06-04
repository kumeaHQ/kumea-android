package co.ke.kumea.ui.screen.note

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val noteRepository: NoteRepository,
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "NoteListViewModel requires a farmId nav argument"
    }

    val notes: StateFlow<List<NoteEntity>> = noteRepository.getActiveByFarm(farmId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            // Parents before children, one pass: farms → fields → notes. A note's
            // CASCADE foreign key needs its field (and the field its farm) present
            // locally first. Each step logs + surfaces errors to the snackbar —
            // never silent. This is the manual stand-in for the deferred
            // single-WorkManager-wakeup that will sync all three together.
            try {
                farmRepository.pushPending()
                Log.d("Sync", "✅ pushPending farms OK")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pushPending farms: ${e.message}", e)
                _errorMessage.value = "Farm push failed: ${e.message}"
            }
            try {
                farmRepository.pullSince()
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
                if (_errorMessage.value == null) _errorMessage.value = "Field push failed: ${e.message}"
            }
            try {
                fieldRepository.pullSince()
                Log.d("Sync", "✅ pullSince fields OK – fields now in Room")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pullSince fields: ${e.message}", e)
                if (_errorMessage.value == null) _errorMessage.value = "Field pull failed: ${e.message}"
            }
            try {
                noteRepository.pushPending()
                Log.d("Sync", "✅ pushPending notes OK")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pushPending notes: ${e.message}", e)
                if (_errorMessage.value == null) _errorMessage.value = "Note push failed: ${e.message}"
            }
            try {
                noteRepository.pullSince()
                Log.d("Sync", "✅ pullSince notes OK — notes now in Room")
            } catch (e: Exception) {
                Log.e("Sync", "❌ pullSince notes: ${e.message}", e)
                if (_errorMessage.value == null) _errorMessage.value = "Note pull failed: ${e.message}"
            }
            _isRefreshing.value = false
        }
    }

    fun onErrorShown() {
        _errorMessage.value = null
    }
}
