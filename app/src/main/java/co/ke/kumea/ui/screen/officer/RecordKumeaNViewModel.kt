package co.ke.kumea.ui.screen.officer

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.KumeaNReceivedRepository
import co.ke.kumea.data.repository.PersonaRepository
import co.ke.kumea.domain.model.Crops
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

data class RecordKumeaNUiState(
    val strainCode: String? = null,
    val packSizeG: Int? = null,
    val batchNumber: String = "",
    val qty: String = "",
    val myAgentId: String? = null,
    val identityLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * "Record Kumea N given" — the officer's and agent's handover entry point
 * (KWAP-03 §7).
 *
 * WHAT THIS SCREEN MUST NEVER GROW, in the same terms as
 * [RegisterFarmerViewModel]: no price field, no quantity × unit-price, no order,
 * no agent picker. There is no money type and no earnings repository injected
 * here, and that absence is the boundary rather than a rule someone has to
 * remember. These are ~395 farmers being GIVEN free research product, and the
 * commission engine is live and accrues backdated to 1 June.
 *
 * THE BATCH NUMBER IS REQUIRED, and it is the whole reason this is a structured
 * record rather than the free-text note Marcus originally proposed. `"gave 3
 * sachets"` in a note body cannot be reconciled at season end and turns the
 * KWAP-02 backfill into archaeology. With the batch, the backfill is a script.
 *
 * [RecordKumeaNUiState.myAgentId] is derived from the signed-in agent and is
 * never chosen. Same derive-don't-check rule as ward: an input can be wrong or
 * spoofed, a derivation cannot.
 */
@HiltViewModel
class RecordKumeaNViewModel @Inject constructor(
    private val repository: KumeaNReceivedRepository,
    private val personaRepository: PersonaRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "RecordKumeaNViewModel requires a farmId nav argument"
    }

    private val _uiState = MutableStateFlow(RecordKumeaNUiState())
    val uiState: StateFlow<RecordKumeaNUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val agent = personaRepository.myAgent()
            _uiState.update { it.copy(myAgentId = agent?.id, identityLoaded = true) }
        }
    }

    fun onStrainChange(code: String) = _uiState.update {
        it.copy(strainCode = if (it.strainCode == code) null else code, error = null)
    }

    fun onPackSizeChange(grams: Int) = _uiState.update {
        it.copy(packSizeG = if (it.packSizeG == grams) null else grams, error = null)
    }

    fun onBatchChange(value: String) = _uiState.update { it.copy(batchNumber = value, error = null) }

    fun onQtyChange(value: String) = _uiState.update { it.copy(qty = value, error = null) }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.isSaving) return

        if (!state.identityLoaded) {
            _uiState.update { it.copy(error = "Still loading your profile — try again in a moment.") }
            return
        }
        // No linked agent means no provenance, and a handover with no recorder
        // cannot be attributed when KWAP-02 backfills these rows into
        // stock_distributions. Refusing is better than a silent orphan.
        val agentId = state.myAgentId
        if (agentId == null) {
            _uiState.update {
                it.copy(error = "This account has no agent profile, so it can't record a handover.")
            }
            return
        }
        val strain = state.strainCode
        if (strain == null) {
            _uiState.update { it.copy(error = "Pick the strain") }
            return
        }
        val packSize = state.packSizeG
        if (packSize == null) {
            _uiState.update { it.copy(error = "Pick the pack size") }
            return
        }
        val batch = state.batchNumber.trim()
        if (batch.isBlank()) {
            _uiState.update { it.copy(error = "Enter the batch number from the sachet") }
            return
        }
        val qty = state.qty.trim().toIntOrNull()
        if (qty == null || qty <= 0) {
            _uiState.update { it.copy(error = "How many sachets? e.g. 3") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.createLocal(
                    farmId = farmId,
                    strainCode = strain,
                    packSizeG = packSize,
                    batchNumber = batch,
                    qty = qty,
                    occurredAt = Clock.System.now().toString(),
                    recordedByAgentId = agentId,
                )
                // NO pushPending() here, unlike RegisterFarmerViewModel. The
                // server routes ship in the KWAP-03 kumea-api patch and are not
                // deployed, so this repository is not bound into the sync set
                // yet — see di/RepositoryModule.kt. The row is saved locally and
                // shows in Zone 1 immediately; it syncs on the first cycle after
                // the binding is enabled.
                _uiState.update { it.copy(isSaving = false) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "kumea n handover save failed", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Couldn't save this handover")
                }
            }
        }
    }

    companion object {
        /**
         * Pack sizes, per the 13 Aug lock: 50 / 100 / 150 g, with 100 g being
         * the forage pack. Plain Ints — the SKU catalogue is KWAP-02 and this
         * shim deliberately has no FK into a table that does not exist.
         */
        val PACK_SIZES_G = listOf(50, 100, 150)

        /**
         * Strain choices are the crop catalogue's keys for now: a rhizobia
         * strain is specific to its legume, so "soybean" identifies the strain
         * a WAO is holding well enough to reconcile against a batch number.
         * The real strain × pack catalogue is KWAP-02.
         */
        val STRAINS = Crops.LEGUMES + Crops.FORAGE

        private const val TAG = "RecordKumeaN"
    }
}
