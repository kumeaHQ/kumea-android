package co.ke.kumea.ui.screen.note

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

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            // Parents before children, one pass: farms → fields → notes. A note's
            // CASCADE foreign key needs its field (and the field its farm) present
            // locally first. Each step is best-effort; a sync failure must not
            // crash pull-to-refresh. This is the manual stand-in for the deferred
            // single-WorkManager-wakeup that will sync all three together.
            try {
                farmRepository.pushPending()
            } catch (e: Exception) {
                // ignore; durable retry lives in the worker
            }
            try {
                farmRepository.pullSince()
            } catch (e: Exception) {
                // ignore; offline or transient
            }
            try {
                fieldRepository.pushPending()
            } catch (e: Exception) {
                // ignore; best-effort
            }
            try {
                fieldRepository.pullSince()
            } catch (e: Exception) {
                // ignore; offline or transient
            }
            try {
                noteRepository.pushPending()
            } catch (e: Exception) {
                // ignore; best-effort
            }
            try {
                noteRepository.pullSince()
            } catch (e: Exception) {
                // ignore; offline or transient
            }
            _isRefreshing.value = false
        }
    }
}
