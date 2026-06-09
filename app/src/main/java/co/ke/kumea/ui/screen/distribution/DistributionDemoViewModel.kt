package co.ke.kumea.ui.screen.distribution

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.FarmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 1a · T5-slice device-demo driver. The thinnest possible surface that
 * exercises the attribution slice end-to-end on a real device, so the Phase-1a
 * gate can be run by hand:
 *
 *   airplane mode → onboard a village_agent (endorsed by an officer) →
 *   register a farmer with that agent as referrer → reconnect → Sync now →
 *   verify both rows in Railway, officer carries no commission path.
 *
 * This is deliberately a debug/demo screen, NOT the persona UI (that is T7).
 * Sync order is Agent-before-Farm here too, matching the SyncWorker Set order:
 * the agent must reach the server before the farmer that references it.
 */
@HiltViewModel
class DistributionDemoViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val farmRepository: FarmRepository,
) : ViewModel() {

    val agents: StateFlow<List<AgentEntity>> = agentRepository.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Officers — the only legitimate endorsers (server enforces officer-only). */
    val officers: StateFlow<List<AgentEntity>> =
        agentRepository.getActiveByRole("extension_officer")
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
     * Push-then-pull, Agent BEFORE Farm — the FK order that lets a farmer
     * referencing a just-onboarded agent land on the server. Every failure is
     * surfaced in the log, never swallowed.
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
            } catch (e: Exception) {
                Log.e("DistDemo", "sync failed", e)
                append("SYNC FAILED: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }
}
