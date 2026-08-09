package co.ke.kumea.ui.screen.farm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.FieldEntity
import co.ke.kumea.data.local.HarvestEntity
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.HarvestRepository
import co.ke.kumea.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FarmHomeUiState(
    val farm: FarmEntity? = null,
    val totalInCents: Long = 0L,
    val totalOutCents: Long = 0L,
    val loading: Boolean = false,
)

@HiltViewModel
class FarmHomeViewModel @Inject constructor(
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    private val harvestRepository: HarvestRepository,
    private val noteRepository: NoteRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(FarmHomeUiState())
    val ui: StateFlow<FarmHomeUiState> = _ui.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes.asStateFlow()

    // Build-2 one-field fast path: the farm's first active field is "the" field
    // for planting/harvest actions. Multi-field selection arrives with the
    // farm/field ticket; these flows already key on fieldId so nothing rebuilds.
    private val _primaryField = MutableStateFlow<FieldEntity?>(null)
    val primaryField: StateFlow<FieldEntity?> = _primaryField.asStateFlow()

    private val _latestHarvest = MutableStateFlow<HarvestEntity?>(null)
    val latestHarvest: StateFlow<HarvestEntity?> = _latestHarvest.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun init(farmId: String) {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            // Observe farm from the active flow
            farmRepository.getAllActive().collect { farms ->
                val farm = farms.find { it.id == farmId }
                farm?.let {
                    _ui.update { s -> s.copy(farm = it, loading = false) }
                }
            }
        }
        viewModelScope.launch {
            noteRepository.getActiveByFarm(farmId).collect { noteList ->
                _notes.value = noteList.filter { it.deletedAt == null }
                computeTotals()
            }
        }
        viewModelScope.launch {
            fieldRepository.getActiveByFarm(farmId).collect { fields ->
                _primaryField.value = fields.firstOrNull()
            }
        }
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            _primaryField
                .flatMapLatest { field ->
                    if (field == null) flowOf(emptyList()) else harvestRepository.getActiveByField(field.id)
                }
                .collect { harvests -> _latestHarvest.value = harvests.firstOrNull() }
        }
    }

    fun refresh() {
        _isRefreshing.value = true
        // Pull in FK order: farm → field → harvest. Errors are surfaced, never
        // swallowed (the old catch(_){} here was a non-negotiable-#3 violation).
        viewModelScope.launch {
            try {
                farmRepository.pullSince()
                fieldRepository.pullSince()
                harvestRepository.pullSince()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FarmHome", "refresh pull failed", e)
                _errorMessage.value = e.message ?: "Couldn't refresh — check your connection"
            }
            _isRefreshing.value = false
        }
    }

    fun onErrorShown() {
        _errorMessage.value = null
    }

    private fun computeTotals() {
        var totalIn = 0L
        var totalOut = 0L
        for (note in _notes.value) {
            note.amountCents?.let { cents ->
                when (note.type) {
                    NoteType.SALE -> totalIn += cents
                    NoteType.PURCHASE, NoteType.ACTIVITY -> totalOut += cents
                }
            }
        }
        _ui.update { it.copy(totalInCents = totalIn, totalOutCents = totalOut) }
    }
}
