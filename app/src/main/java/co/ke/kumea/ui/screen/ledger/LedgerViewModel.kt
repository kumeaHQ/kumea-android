package co.ke.kumea.ui.screen.ledger

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.FarmLedger
import co.ke.kumea.data.repository.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import kotlinx.datetime.Clock
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

data class LedgerUiState(
    val isLoading: Boolean = false,
    val ledger: FarmLedger? = null,
    /** "HH:MM" (EAT) of the last *successful* load — drives the honesty stamp. */
    val asOfLabel: String? = null,
    /** True when the latest refresh failed but we still have last-known data. */
    val isStale: Boolean = false,
    /** One-shot error for the snackbar. */
    val errorMessage: String? = null,
    /**
     * True when the server has no ledger for this farm yet (404) — the farm
     * hasn't synced. An EXPECTED state, not an error: the screen shows a calm
     * "appears after sync" message instead of a failure (field feedback 11 Jul).
     */
    val notSyncedYet: Boolean = false,
)

/**
 * Ticket 3.3 — the Ledger is a read-only, server-computed P&L rollup. This
 * ViewModel fetches it for one farm and is honest about staleness:
 *
 *  - The rollup reflects *synced* notes, so the screen stamps "as of HH:MM" and
 *    tells the farmer to pull-to-refresh their notes first for the latest.
 *  - If a refresh fails (offline / network), we keep showing the last-known
 *    rollup flagged stale rather than blanking the screen — and we ALWAYS
 *    surface the error (no silent catch, AC #17). We never pretend the rollup
 *    includes unsynced local notes.
 */
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val farmId: String = checkNotNull(savedStateHandle["farmId"]) {
        "LedgerViewModel requires a farmId nav argument"
    }

    private val _uiState = MutableStateFlow(LedgerUiState())
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val ledger = ledgerRepository.getFarmLedger(farmId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ledger = ledger,
                        asOfLabel = nowEatLabel(),
                        isStale = false,
                        errorMessage = null,
                    )
                }
                Log.d("Ledger", "✅ farm ledger loaded: net=${ledger.netCents}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 404 && _uiState.value.ledger == null) {
                    // Farm not on the server yet — expected pre-sync, stay calm.
                    Log.d("Ledger", "ledger 404 — farm not synced yet")
                    _uiState.update {
                        it.copy(isLoading = false, notSyncedYet = true, errorMessage = null)
                    }
                } else {
                    Log.e("Ledger", "❌ farm ledger fetch failed: HTTP ${e.code()}", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isStale = it.ledger != null,
                            errorMessage = "Couldn't load P&L: HTTP ${e.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                // No silent catch (standing rule). Log, keep last-known if any,
                // mark it stale, and surface the failure to the user.
                Log.e("Ledger", "❌ farm ledger fetch failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isStale = it.ledger != null,
                        errorMessage = "Couldn't load P&L: ${e.message ?: "network error"}",
                    )
                }
            }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Now, as HH:MM in East Africa Time. EAT is a fixed UTC+3 with no DST, so a
     * fixed offset is exact and needs no IANA tz database (display-edge only).
     */
    private fun nowEatLabel(): String {
        val nowEat = Clock.System.now().toLocalDateTime(UtcOffset(hours = 3).asTimeZone())
        return "%02d:%02d".format(nowEat.hour, nowEat.minute)
    }
}
