package co.ke.kumea.ui.screen.distribution

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

/**
 * Phase 1 close-gate device-demo driver. The thinnest possible surface that
 * exercises ALL THREE distribution write paths end-to-end on a real device, so
 * the Phase-1 master gate can be run by hand from a single screen:
 *
 *   airplane mode → onboard a village_agent (endorsed by an officer) →
 *   register a farmer with that agent as referrer → record a sale attributed to
 *   that agent → reconnect → Sync now → verify all three rows in Railway with
 *   money intact, officer carries no commission path.
 *
 * P1-T5 added the Order leg: the sale saves offline (createLocal) and pushes via
 * the same Agent-before-Farm-before-Order order the SyncWorker uses, with the
 * per-repo FK guard deferring any row whose parent hasn't reached the server.
 * This is deliberately a debug/demo screen, NOT the persona UI (that is T7).
 */
@HiltViewModel
class DistributionDemoViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val farmRepository: FarmRepository,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    val agents: StateFlow<List<AgentEntity>> = agentRepository.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Officers — the only legitimate endorsers (server enforces officer-only). */
    val officers: StateFlow<List<AgentEntity>> =
        agentRepository.getActiveByRole("extension_officer")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val farms: StateFlow<List<FarmEntity>> = farmRepository.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val orders: StateFlow<List<OrderEntity>> = orderRepository.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _log = MutableStateFlow("Ready.")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun append(line: String) {
        _log.value = "${_log.value}\n• $line"
    }

    /** Onboard an extension_officer locally (so a VA can be endorsed offline). */
    fun onboardOfficer(region: String) {
        viewModelScope.launch {
            val id = agentRepository.createLocal(role = "extension_officer", region = region)
            append("Onboarded officer (pending) id=${id.take(8)} region=$region")
        }
    }

    /** Onboard a village_agent, optionally endorsed by an officer already on device. */
    fun onboardVillageAgent(region: String, endorsedById: String?) {
        viewModelScope.launch {
            val id = agentRepository.createLocal(
                role = "village_agent",
                region = region,
                endorsedById = endorsedById,
            )
            append("Onboarded village_agent (pending) id=${id.take(8)} endorsedBy=${endorsedById?.take(8) ?: "none"}")
        }
    }

    /** Register a farmer (a Farm) attributed to a referrer agent. */
    fun registerFarmerWithReferrer(name: String, referrerAgentId: String?) {
        viewModelScope.launch {
            val id = farmRepository.createLocal(
                name = name,
                locationLat = null,
                locationLng = null,
                waterSource = null,
                referrerAgentId = referrerAgentId,
            )
            append("Registered farmer (pending) farm=${id.take(8)} referrer=${referrerAgentId?.take(8) ?: "none"}")
        }
    }

    /**
     * Record the close-gate Biofix sale OFFLINE: KES 1,000 (100000 cents),
     * channel=agent, attributed to the most-recent commission-eligible agent, on
     * the most-recent farm. Money stays Long cents end-to-end — never a Double.
     * The picker-equivalent guard lives here too: an officer can never be the
     * selling agent.
     */
    fun recordDemoSale() {
        viewModelScope.launch {
            val farm = farms.value.firstOrNull()
            if (farm == null) {
                append("No farmer yet — register one first")
                return@launch
            }
            val agent = agents.value.firstOrNull {
                it.role != "extension_officer" && it.agentCode.isNotBlank()
            }
            if (agent == null) {
                append("No sellable agent yet — onboard a village_agent first")
                return@launch
            }
            val id = orderRepository.createLocal(
                farmerId = farm.id,
                agentCode = agent.agentCode,
                dealerId = null,
                sku = "BFX-100G",
                qty = 1,
                unitPrice = 100_000L, // KES 1,000 in cents
                channel = "agent",
                date = Clock.System.now().toString(),
            )
            append("Recorded sale (pending) order=${id.take(8)} KES 1,000 channel=agent agent=${agent.agentCode}")
        }
    }

    /**
     * Push-then-pull in FK order — Agent, then Farm, then Order — the same order
     * the SyncWorker Set uses. Each leg's pushPending defers a row whose parent
     * isn't on the server yet (FK guard), so a partial sync leaves the dependent
     * row PENDING for the next cycle rather than failing. Orders still pending
     * after the push are reported as deferred. Every failure is surfaced in the
     * log; CancellationException is re-thrown, never swallowed.
     */
    fun syncNow() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val ap = agentRepository.pushPending()
                val al = agentRepository.pullSince()
                append("Agents synced: $ap pushed, $al pulled")
                val fp = farmRepository.pushPending()
                val fl = farmRepository.pullSince()
                append("Farms synced: $fp pushed, $fl pulled")
                val op = orderRepository.pushPending()
                val ol = orderRepository.pullSince()
                val deferred = orderRepository.countPendingSync()
                val deferNote =
                    if (deferred > 0) " — $deferred deferred (FK parent not synced yet, will retry)" else ""
                append("Orders synced: $op pushed, $ol pulled$deferNote")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DistDemo", "sync failed", e)
                append("SYNC FAILED: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }
}
