package co.ke.kumea.ui.screen.farm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.FieldEntity
import co.ke.kumea.data.local.HarvestEntity
import co.ke.kumea.data.local.KumeaNReceivedEntity
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.PlantingEntity
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.HarvestRepository
import co.ke.kumea.data.repository.KumeaNReceivedRepository
import co.ke.kumea.data.repository.NoteRepository
import co.ke.kumea.data.repository.PlantingRepository
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

/**
 * NO MONEY TOTALS HERE ANY MORE (KWAP-03 §5.4).
 *
 * `totalInCents` / `totalOutCents` and the "Invested so far" card they fed are
 * gone from the farmer's farm page. Two reasons, and the second is the sharper
 * one: this season carries no commercial spine at all — nothing is sold through
 * the app — and for a farmer who was GIVEN the product, "KES 0 invested" is
 * worse than absent. It invites a question with no good answer.
 *
 * `LedgerScreen` and `OrderCreateScreen` are untouched. The money surface
 * belongs to the agent persona and still exists there; what changed is that it
 * no longer appears on the page a research farmer opens.
 */
data class FarmHomeUiState(
    val farm: FarmEntity? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class FarmHomeViewModel @Inject constructor(
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    private val harvestRepository: HarvestRepository,
    private val noteRepository: NoteRepository,
    private val kumeaNReceivedRepository: KumeaNReceivedRepository,
    private val plantingRepository: PlantingRepository,
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

    /**
     * The season's planting (KWAP-03-V2 §2.3). Replaces reading
     * `fields.plantedAt`, which is retired in place — the Planted timeline row
     * and the season record both derive from THIS flow now, which keeps them on
     * the same read as their tick (§2.1's rule).
     */
    private val _latestPlanting = MutableStateFlow<PlantingEntity?>(null)
    val latestPlanting: StateFlow<PlantingEntity?> = _latestPlanting.asStateFlow()

    /** Zone 1: what this farmer actually received (KWAP-03 §7). */
    private val _kumeaNReceived = MutableStateFlow<List<KumeaNReceivedEntity>>(emptyList())
    val kumeaNReceived: StateFlow<List<KumeaNReceivedEntity>> = _kumeaNReceived.asStateFlow()

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
            }
        }
        viewModelScope.launch {
            kumeaNReceivedRepository.getActiveByFarm(farmId).collect { _kumeaNReceived.value = it }
        }
        viewModelScope.launch {
            fieldRepository.getActiveByFarm(farmId).collect { fields ->
                _primaryField.value = fields.firstOrNull()
            }
        }
        viewModelScope.launch {
            plantingRepository.getActiveByFarm(farmId).collect { plantings ->
                _latestPlanting.value = plantings.firstOrNull()
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
}
