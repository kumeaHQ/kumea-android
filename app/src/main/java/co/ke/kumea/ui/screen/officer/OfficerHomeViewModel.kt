package co.ke.kumea.ui.screen.officer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.repository.AgentRepository
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.PersonaRepository
import co.ke.kumea.domain.model.Persona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The officer's ward surface — derived entirely from the channel-wide agent
 * roster (non-commercial). There is, by construction, NO money/earnings field
 * anywhere in this state: the officer's zero-commercial-surface law is upheld by
 * the data shape, not by hiding a field.
 *
 * A village_agent is endorseable when it shares the officer's ward; [endorsedByMe]
 * marks ones this officer already endorses.
 */
data class OfficerUiState(
    val ward: String? = null,
    val myAgentId: String? = null,
    val endorseable: List<AgentEntity> = emptyList(),
    val activeAgentsInWard: Int = 0,
    val endorsedByMeCount: Int = 0,
    /**
     * Farmers this officer has registered (KWAP-01 step 4). Read from Room via
     * `registeredByAgentId`, so it counts rows that are still pending push too —
     * a WAO who registered five farmers on a bus with no signal should see five.
     */
    val farmersRegisteredByMe: Int = 0,
    val loaded: Boolean = false,
)

/**
 * Extension_officer home (P1-T7): endorsement + ward outcomes. No earnings, no
 * commission, no price/margin — this ViewModel has no money type at all, and the
 * earnings repository is not even injected.
 *
 * Ward outcomes are the non-commercial figures derivable on-device: active
 * agents in ward and village_agents this officer has endorsed, both from the
 * channel-wide /agents roster — plus, since KWAP-01 step 4, farmers she has
 * registered, from `GET /farms?registeredBy=me` cached into Room.
 *
 * Ward SALES totals are still absent, and still honestly flagged. They need a
 * server ward report that does not exist, and they are money — which this
 * surface will never show.
 */
@HiltViewModel
class OfficerHomeViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val farmRepository: FarmRepository,
    private val personaRepository: PersonaRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val myAgent = MutableStateFlow<AgentEntity?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val myRegistrations: Flow<List<FarmEntity>> = myAgent.flatMapLatest { me ->
        if (me == null) flowOf(emptyList()) else farmRepository.getRegisteredBy(me.id)
    }

    val ui: StateFlow<OfficerUiState> =
        combine(myAgent, agentRepository.getAllActive(), myRegistrations) { me, all, registered ->
            val ward = me?.ward
            val inWard = if (ward.isNullOrBlank()) emptyList()
            else all.filter { it.ward == ward && it.status == "active" }
            OfficerUiState(
                ward = ward,
                myAgentId = me?.id,
                endorseable = inWard
                    .filter { it.role != Persona.ROLE_EXTENSION_OFFICER }
                    .sortedBy { it.agentCode },
                activeAgentsInWard = inWard.size,
                endorsedByMeCount = if (me == null) 0 else all.count { it.endorsedById == me.id },
                farmersRegisteredByMe = registered.size,
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OfficerUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    init {
        viewModelScope.launch {
            myAgent.value = personaRepository.myAgent()
        }
    }

    /**
     * Endorse a village_agent (offline-first). The local UPDATE is saved
     * immediately so it survives offline; the push is best-effort and, if it
     * fails (e.g. no signal), the row stays pending and syncs later — surfaced
     * honestly, never as a hard error or a silent drop.
     */
    fun endorse(agentId: String) {
        val officerAgentId = ui.value.myAgentId ?: run {
            _message.value = "Couldn't find your officer profile yet — pull to refresh."
            return
        }
        viewModelScope.launch {
            agentRepository.endorse(agentId, officerAgentId)
            try {
                agentRepository.pushPending()
                agentRepository.pullSince()
                _message.value = "Endorsed."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("OfficerHome", "endorse push deferred", e)
                _message.value = "Endorsement saved — it'll sync when you're back online."
            }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                agentRepository.pushPending()
                agentRepository.pullSince()
                // Registrations too, so the count on this card is not stale
                // relative to the directory the officer taps through to.
                farmRepository.pushPending()
                farmRepository.pullRegisteredByMe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OfficerHome", "ward refresh failed", e)
                _message.value = "Couldn't refresh ward data: ${e.message}"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
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
