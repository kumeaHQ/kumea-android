package co.ke.kumea.ui.screen.order

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.OrderRepository
import co.ke.kumea.util.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

data class FarmerOption(val id: String, val name: String)

data class AgentOption(val agentCode: String, val role: String, val region: String)

/** Biofix pack sizes. A fixed list until the SKU catalogue becomes an entity. */
val SkuOptions = listOf("BFX-100G", "BFX-500G")

data class OrderFormState(
    val farmers: List<FarmerOption> = emptyList(),
    val selectedFarmerId: String? = null,
    val sku: String = SkuOptions.first(),
    val qty: String = "1",
    val unitPrice: String = "",
    // No default — channel is load-bearing and the user must choose explicitly.
    val channel: String? = null,
    val agents: List<AgentOption> = emptyList(),
    val selectedAgentCode: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** Live, float-free preview of the typed unit price in cents (or null). */
    val parsedUnitPrice: Long? get() = if (unitPrice.isBlank()) null else Money.parseToCents(unitPrice)

    val parsedQty: Int? get() = qty.trim().toIntOrNull()

    /**
     * Line total preview: the ONE multiplication, via Money.lineTotalCents
     * (Long math, overflow-checked) — never Int arithmetic. Null while either
     * input is invalid or the product overflows Long.
     */
    val lineTotalPreview: Long?
        get() {
            val q = parsedQty ?: return null
            val p = parsedUnitPrice ?: return null
            if (q <= 0 || p <= 0) return null
            return try {
                Money.lineTotalCents(q, p)
            } catch (e: ArithmeticException) {
                null
            }
        }
}

/**
 * Create-Order ViewModel (P1-T3) — same shape as NoteDetailViewModel. The save
 * path is ONLINE (OrderRepository.createOnline): the server must accept the
 * order before anything lands in Room, so an officer agent_code or a missing
 * channel is rejected with the server's message right on the screen. Offline
 * creation is T5.
 */
@HiltViewModel
class OrderCreateViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val authRepository: AuthRepository,
    farmRepository: FarmRepository,
    agentRepository: AgentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Optional preselect when entered from a farm's detail screen.
    private val navFarmId: String? = savedStateHandle["farmId"]

    private val _uiState = MutableStateFlow(OrderFormState())
    val uiState: StateFlow<OrderFormState> = _uiState.asStateFlow()

    init {
        // Farmer picker: the user's local farms — each farm IS a farmer record
        // (Phase-1a registration creates a Farm). Preselect the nav-arg farm.
        viewModelScope.launch {
            farmRepository.getAllActive().collect { farms ->
                val options = farms.map { FarmerOption(it.id, it.name) }
                _uiState.update { state ->
                    state.copy(
                        farmers = options,
                        selectedFarmerId = state.selectedFarmerId
                            ?: navFarmId?.takeIf { id -> options.any { it.id == id } }
                            ?: options.firstOrNull()?.id,
                    )
                }
            }
        }

        // Agent picker: commission-eligible agents only. Officers are excluded
        // here for the same reason the server rejects them — they can never be
        // the commercial attribution on a sale. Locally-onboarded agents whose
        // server agentCode hasn't been assigned yet (blank) are unusable too.
        viewModelScope.launch {
            agentRepository.getAllActive().collect { agents ->
                val options = agents
                    .filter { it.role != "extension_officer" && it.agentCode.isNotBlank() }
                    .map { AgentOption(it.agentCode, it.role, it.region) }
                _uiState.update { it.copy(agents = options) }
            }
        }

        // Auto-populate agent_code when the logged-in user IS an agent
        // (Agent.linkedUserId == me). Best-effort: a failed lookup just means
        // no preselect — logged, never fatal, cancellation re-thrown.
        viewModelScope.launch {
            try {
                val myUserId = authRepository.me().id
                agentRepository.getAllActive().collect { agents ->
                    val mine = agents.firstOrNull {
                        it.linkedUserId == myUserId &&
                            it.role != "extension_officer" &&
                            it.agentCode.isNotBlank()
                    }
                    if (mine != null) {
                        _uiState.update { state ->
                            if (state.selectedAgentCode == null) {
                                state.copy(selectedAgentCode = mine.agentCode)
                            } else {
                                state
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("OrderCreate", "agent_code auto-populate skipped: ${e.message}")
            }
        }
    }

    fun onFarmerSelected(farmerId: String) = _uiState.update { it.copy(selectedFarmerId = farmerId) }
    fun onSkuSelected(sku: String) = _uiState.update { it.copy(sku = sku) }
    fun onQtyChange(qty: String) = _uiState.update { it.copy(qty = qty) }
    fun onUnitPriceChange(unitPrice: String) = _uiState.update { it.copy(unitPrice = unitPrice) }
    fun onChannelSelected(channel: String) = _uiState.update { it.copy(channel = channel) }

    /** Toggle an agent chip — tapping the selected one clears it (optional field). */
    fun onAgentSelected(agentCode: String?) = _uiState.update {
        it.copy(selectedAgentCode = if (it.selectedAgentCode == agentCode) null else agentCode)
    }

    fun saveOrder(onSuccess: () -> Unit) {
        val state = _uiState.value

        val farmerId = state.selectedFarmerId
        if (farmerId == null) {
            _uiState.update { it.copy(error = "No farmer available — pull to refresh on the farm list first") }
            return
        }

        // Zero/negative qty and price are rejected explicitly here, again by the
        // server DTO, and again by the DB CHECK — no silent coercion anywhere.
        val qty = state.parsedQty
        if (qty == null || qty <= 0) {
            _uiState.update { it.copy(error = "Quantity must be a positive whole number") }
            return
        }
        val unitPrice = state.parsedUnitPrice
        if (unitPrice == null) {
            _uiState.update { it.copy(error = "Enter a valid unit price (e.g. 1000 or 1000.50)") }
            return
        }
        if (unitPrice <= 0) {
            _uiState.update { it.copy(error = "Unit price must be greater than zero") }
            return
        }
        val channel = state.channel
        if (channel == null) {
            _uiState.update { it.copy(error = "Choose a sales channel") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                orderRepository.createOnline(
                    farmerId = farmerId,
                    agentCode = state.selectedAgentCode,
                    dealerId = null, // dealer flows are quarantined (MEA cohort)
                    sku = state.sku,
                    qty = qty,
                    unitPrice = unitPrice,
                    channel = channel,
                    date = Clock.System.now().toString(),
                )
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OrderCreate", "order create failed", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to record sale")
                }
            }
        }
    }
}
