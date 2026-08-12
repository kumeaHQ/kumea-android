package co.ke.kumea.ui.screen.officer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/**
 * "Farmers I registered" — the officer's directory (KWAP-01 step 4).
 *
 * NOT WARD-SCOPED, and that is the settled scope rather than a shortfall. A
 * ward-wide view ("every farm in Chepterwai, including other agents'") needs a
 * ward column and its own authorisation question, and both were deferred
 * (KWAP-STEP2-DECISIONS §4). What a WAO running KWAP actually needs is the list
 * of people she typed in, which is what `GET /farms?registeredBy=me` returns.
 *
 * Reads from ROOM, not from the response. The network fetch upserts and the list
 * is a Room Flow, so the directory works on a phone with no signal and a
 * just-saved registration appears before any push completes — the offline-first
 * contract the rest of the app keeps.
 */
data class FarmerDirectoryUiState(
    val farmers: List<FarmEntity> = emptyList(),
    val ward: String? = null,
    val identityLoaded: Boolean = false,
    val hasAgentProfile: Boolean = true,
)

@HiltViewModel
class FarmerDirectoryViewModel @Inject constructor(
    private val farmRepository: FarmRepository,
    private val personaRepository: PersonaRepository,
) : ViewModel() {

    private val myAgentId = MutableStateFlow<String?>(null)
    private val identity = MutableStateFlow(FarmerDirectoryUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val registeredFarms = myAgentId.flatMapLatest { agentId ->
        // A null agent id means no linked Agent — a plain farmer who should
        // never have reached this route. Empty list, not every farm on the
        // device: `registeredByAgentId = null` matches nothing by design.
        if (agentId == null) flowOf(emptyList()) else farmRepository.getRegisteredBy(agentId)
    }

    val ui: StateFlow<FarmerDirectoryUiState> =
        combine(registeredFarms, identity) { rows, id -> id.copy(farmers = rows) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FarmerDirectoryUiState())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            val agent = personaRepository.myAgent()
            myAgentId.value = agent?.id
            identity.value = identity.value.copy(
                ward = agent?.ward,
                identityLoaded = true,
                hasAgentProfile = agent != null,
            )
            refresh()
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                // Push first: a registration saved offline should reach the
                // server before we ask the server what we registered, or it
                // briefly looks like it went missing.
                farmRepository.pushPending()
                farmRepository.pullRegisteredByMe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // 403 is terminal and specific: the caller is not an active
                // officer or village agent, so the endpoint refuses. Saying that
                // beats an empty list that looks like "no farmers yet".
                _message.value = if (e.code() == 403) {
                    "Your account can't list registrations. Ask Kumea to check your agent profile."
                } else {
                    "Couldn't refresh your farmers (${e.code()}). Showing what's on this phone."
                }
                Log.e(TAG, "directory refresh failed", e)
            } catch (e: Exception) {
                Log.e(TAG, "directory refresh failed", e)
                _message.value = "Couldn't reach the server. Showing what's on this phone."
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        const val TAG = "FarmerDirectory"
    }
}
