package co.ke.kumea.ui.screen.agent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.repository.AuthRepository
import co.ke.kumea.data.repository.CommissionRepository
import co.ke.kumea.data.repository.EarningsSurface
import co.ke.kumea.data.repository.OrderRepository
import co.ke.kumea.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The earnings card's load state (gated/inert in Phase 1). */
data class EarningsUiState(
    val loading: Boolean = true,
    val surface: EarningsSurface? = null,
    val error: String? = null,
)

/**
 * Village_agent home (P1-T7): own attributed Sales + the gated Earnings surface.
 *
 * SALES are the agent's own attributed orders — filtered locally by
 * agentId == my Agent.id (the stable T8 attribution key, not the code). EARNINGS
 * come from GET /commission/me and are INERT in Phase 1 (KES 0.00, sachets
 * counted). Money never leaves Long cents until Money.formatCents at the display
 * edge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VillageAgentHomeViewModel @Inject constructor(
    private val orderRepository: OrderRepository,
    private val commissionRepository: CommissionRepository,
    private val personaRepository: PersonaRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val myAgentId = MutableStateFlow<String?>(null)

    /** Own attributed sales — orders whose agentId is this agent's stable UUID. */
    val sales: StateFlow<List<OrderEntity>> = myAgentId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else orderRepository.getAllActive().map { orders -> orders.filter { it.agentId == id } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _earnings = MutableStateFlow(EarningsUiState())
    val earnings: StateFlow<EarningsUiState> = _earnings.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    init {
        viewModelScope.launch {
            myAgentId.value = personaRepository.myAgent()?.id
        }
        loadEarnings()
    }

    fun loadEarnings() {
        viewModelScope.launch {
            _earnings.value = _earnings.value.copy(loading = true, error = null)
            _earnings.value = try {
                EarningsUiState(loading = false, surface = commissionRepository.myEarnings(), error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VillageAgentHome", "earnings load failed", e)
                EarningsUiState(
                    loading = false,
                    surface = null,
                    error = "Couldn't load earnings. Pull to retry.",
                )
            }
        }
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
