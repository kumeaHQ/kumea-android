package co.ke.kumea.ui.screen.order

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.OrderRepository
import co.ke.kumea.domain.model.KumeaNPack
import co.ke.kumea.domain.model.Persona
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

/**
 * WHO THE CALLER IS, resolved once from their own linked Agent (KWAP-06 §3.2/3.3).
 *
 * This replaces the "Sold by agent" picker, which listed every commission-eligible
 * agent on the device and let the operator choose one. Attribution decides who
 * gets paid by a live, backdated commission engine — it is a fact about the
 * caller, never a selection. `agentCode` rides along as the display denorm only;
 * the server re-derives it from `agentId`.
 */
data class SellerIdentity(
    val agentId: String,
    /** May be blank while the server has not canonicalised it. Display only. */
    val agentCode: String,
    /** `AgentEntity.role`, verbatim. The channel and attribution both derive from it. */
    val role: String,
) {
    /**
     * The order channel this seller records under (§3.3). DERIVED — the screen
     * has no channel chips any more.
     *
     * Null means "this role has no sanctioned channel", and the save refuses
     * rather than guessing. `cooperative` is the live example: it is
     * commission-eligible and maps to the VILLAGE_AGENT persona, but none of the
     * server's five channels describes it. Picking one would attribute a sale
     * down a path nobody chose, against an engine that pays out.
     */
    val channel: String?
        get() = when (role) {
            ROLE_VILLAGE_AGENT -> "agent"
            // A dealer buys stock and resells; the dealer's margin is Order-level,
            // not a commission rule (see scripts/seed-commission-rule.ts). So the
            // channel is recorded and NOTHING is attributed.
            ROLE_AGRO_DEALER -> "dealer"
            else -> null
        }

    /**
     * WHO GETS PAID (§3.2) — the caller, and only when the caller is a village
     * agent. Null for every other role, which is what stops a dealer sale
     * accruing commission it was never owed.
     */
    val attributedAgentId: String?
        get() = if (role == ROLE_VILLAGE_AGENT) agentId else null

    val attributedAgentCode: String?
        get() = if (role == ROLE_VILLAGE_AGENT) agentCode.takeIf { it.isNotBlank() } else null

    companion object {
        const val ROLE_VILLAGE_AGENT = "village_agent"
        const val ROLE_AGRO_DEALER = "agro_dealer"
    }
}

data class OrderFormState(
    val farmers: List<FarmerOption> = emptyList(),
    val selectedFarmerId: String? = null,
    /** The catalogue entry. Price follows from it — there is no price input. */
    val pack: KumeaNPack = KumeaNPack.G150,
    val qty: String = "1",
    /** Null until the caller's own Agent record has been read. */
    val seller: SellerIdentity? = null,
    val identityLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** DERIVED from the pack, never typed. The single lookup site. */
    val unitPriceCents: Long get() = pack.farmerPriceCents

    val parsedQty: Int? get() = qty.trim().toIntOrNull()

    /**
     * Line total: the ONE multiplication, via Money.lineTotalCents (Long math,
     * overflow-checked) — never Int arithmetic. Null while qty is invalid.
     */
    val lineTotalCents: Long?
        get() {
            val q = parsedQty ?: return null
            if (q <= 0) return null
            return try {
                Money.lineTotalCents(q, unitPriceCents)
            } catch (e: ArithmeticException) {
                null
            }
        }

    /** The caller cannot record a sale until we know who they are and how they sell. */
    val canRecord: Boolean
        get() = identityLoaded && seller?.channel != null && selectedFarmerId != null
}

/**
 * Record a Kumea N sale (P1-T3 → offline-first P1-T5 → KWAP-06 money rules).
 *
 * ── WHAT KWAP-06 REMOVED, AND WHY ───────────────────────────────────────────
 *
 * This screen used to take three inputs that decided money:
 *
 *   ① a free-text **unit price**   → now derived from the pack (`PriceMatrix`)
 *   ② a **channel** chip row       → now derived from the caller's agent role
 *   ③ a **"Sold by" agent picker** → now derived from the caller, or null
 *
 * All three fed a commission engine that is LIVE and accrues backdated to
 * 1 June, with a real agent recording real sales. A typed price is liability
 * computed from a number a human could fat-finger; a picked "sold by" is
 * attribution the operator could point at anyone on the device. Derive, don't
 * check — the same rule that made `ward` and `registeredByAgentId` server-derived.
 *
 * The save path stays OFFLINE-FIRST (`OrderRepository.createLocal`): the sale
 * lands in Room as a pending CREATE and SyncWorker pushes it, so a sale recorded
 * with no signal is never lost. Deriving attribution does not depend on the
 * network either — the caller's agent UUID is already on the device.
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

        // ── The seller: the caller's OWN agent record, and nothing else ──────
        //
        // A blank agentCode no longer disqualifies anyone. The attribution key
        // is the stable UUID (P1-T8) and it is correct before the server has
        // canonicalised the code; the code is a display denorm the server
        // re-derives. The old picker required a non-blank code because it was
        // showing a label — that was a display constraint enforced on money.
        viewModelScope.launch {
            try {
                val myUserId = authRepository.me().id
                agentRepository.getAllActive().collect { agents ->
                    val mine = agents.firstOrNull { it.linkedUserId == myUserId }
                    _uiState.update { state ->
                        state.copy(
                            seller = mine?.let {
                                SellerIdentity(
                                    agentId = it.id,
                                    agentCode = it.agentCode,
                                    role = it.role,
                                )
                            },
                            identityLoaded = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline `me()` failure must not strand the screen in a
                // permanent "loading" state — mark it loaded with no seller, so
                // the refusal below explains itself instead of spinning.
                Log.w("OrderCreate", "seller identity lookup failed: ${e.message}")
                _uiState.update { it.copy(identityLoaded = true) }
            }
        }
    }

    fun onFarmerSelected(farmerId: String) = _uiState.update { it.copy(selectedFarmerId = farmerId) }
    fun onPackSelected(pack: KumeaNPack) = _uiState.update { it.copy(pack = pack) }
    fun onQtyChange(qty: String) = _uiState.update { it.copy(qty = qty) }

    /**
     * Why a caller cannot record a sale, in words a WAO can act on. Null when
     * they can. Every branch names the actual blocker rather than disabling a
     * button silently.
     */
    fun blockedReason(state: OrderFormState): String? = when {
        !state.identityLoaded -> "Still loading your agent profile — try again in a moment."
        state.seller == null ->
            "This account has no agent record, so a sale has nobody to attribute to. " +
                "Ask HQ to link your agent profile before recording sales."
        state.seller.role == Persona.ROLE_EXTENSION_OFFICER ->
            // Structurally unreachable — an officer never reaches the agent home
            // (Persona.allowsEarnings) and the server rejects officer attribution
            // outright. Stated anyway: the officer boundary is defended at every
            // layer, never assumed from the layer above.
            "Extension officers never record sales."
        state.seller.channel == null ->
            "Role '${state.seller.role}' has no sales channel defined, so this sale " +
                "cannot be attributed. Recording it would guess who gets paid."
        state.farmers.isEmpty() -> "No farmers yet — register one first, or pull to refresh."
        else -> null
    }

    fun saveOrder(onSuccess: () -> Unit) {
        val state = _uiState.value

        blockedReason(state)?.let { reason ->
            _uiState.update { it.copy(error = reason) }
            return
        }

        val farmerId = state.selectedFarmerId
        if (farmerId == null) {
            _uiState.update { it.copy(error = "Choose the farmer this sale is for") }
            return
        }

        // Quantity is the only number a human still enters on this screen.
        // Rejected here, again by the server DTO, and again by the DB CHECK.
        val qty = state.parsedQty
        if (qty == null || qty <= 0) {
            _uiState.update { it.copy(error = "Quantity must be a positive whole number") }
            return
        }
        if (state.lineTotalCents == null) {
            _uiState.update { it.copy(error = "That quantity is too large to price") }
            return
        }

        // Not null — blockedReason has already refused every path that leaves
        // either of these unresolved.
        val seller = state.seller!!
        val channel = seller.channel!!

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                orderRepository.createLocal(
                    farmerId = farmerId,
                    // DERIVED: the caller, and only when they are a village agent.
                    agentId = seller.attributedAgentId,
                    agentCode = seller.attributedAgentCode,
                    dealerId = null, // dealer flows are quarantined (MEA cohort)
                    sku = state.pack.sku,
                    qty = qty,
                    // DERIVED from the pack. There is no price input to disagree with.
                    unitPrice = state.unitPriceCents,
                    // DERIVED from the caller's role.
                    channel = channel,
                    date = Clock.System.now().toString(),
                )
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OrderCreate", "order save failed", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Failed to record sale")
                }
            }
        }
    }
}
