package co.ke.kumea.ui.screen.officer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.PersonaRepository
import co.ke.kumea.util.normalizeKenyanPhone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterFarmerUiState(
    val farmerName: String = "",
    val farmerPhone: String = "",
    val shambaName: String = "",
    val cropType: String? = null,
    val acres: String = "",
    /** Derived from the signed-in agent, shown read-only. Never an input. */
    val ward: String? = null,
    val myAgentId: String? = null,
    val identityLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** The shamba's name falls back to the person's — see the note in [RegisterFarmerViewModel]. */
    val effectiveShambaName: String
        get() = shambaName.trim().ifBlank { farmerName.trim() }
}

/**
 * Register a farmer (KWAP-01 step 4) — the officer's and village agent's entry
 * point, as opposed to `FarmCreateScreen`, which is a farmer describing their
 * own shamba.
 *
 * WHAT THIS SCREEN MUST NEVER GROW. No price, no order, no commission, no agent
 * picker. The officer boundary is not "officers can't add farmers" — Marcus was
 * explicit that both officers and agents can — it is "officers never touch
 * money" (KWAP-01 §5), and that boundary is kept by this ViewModel having no
 * money type and no earnings repository injected, the same way
 * [OfficerHomeViewModel] does. A field added here that carries a shilling
 * silently reopens it.
 *
 * WHY THE WARD IS SHOWN AND NOT ASKED. A registration's ward is the registrar's
 * ward; the officer cannot express another one, so there is nothing to validate
 * and nothing to get wrong. Derive, don't check — and don't store either: it is
 * recoverable through `registeredByAgentId` → `AgentEntity.ward`, so a copy on
 * the farm could only ever disagree with its source.
 */
@HiltViewModel
class RegisterFarmerViewModel @Inject constructor(
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    private val personaRepository: PersonaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterFarmerUiState())
    val uiState: StateFlow<RegisterFarmerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val agent = personaRepository.myAgent()
            _uiState.update {
                it.copy(ward = agent?.ward, myAgentId = agent?.id, identityLoaded = true)
            }
        }
    }

    fun onFarmerNameChange(value: String) = _uiState.update { it.copy(farmerName = value, error = null) }
    fun onFarmerPhoneChange(value: String) = _uiState.update { it.copy(farmerPhone = value, error = null) }
    fun onShambaNameChange(value: String) = _uiState.update { it.copy(shambaName = value) }
    fun onAcresChange(value: String) = _uiState.update { it.copy(acres = value) }

    /** Tapping the selected crop clears it — a register entry may honestly not know yet. */
    fun onCropChange(key: String) = _uiState.update {
        it.copy(cropType = if (it.cropType == key) null else key)
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.isSaving) return

        val farmerName = state.farmerName.trim()
        if (farmerName.isBlank()) {
            _uiState.update { it.copy(error = "Enter the farmer's name") }
            return
        }

        // No linked Agent means no provenance: the row would be stamped by
        // neither the device nor the server, so it would land in nobody's
        // register and be indistinguishable from the officer's own shamba.
        // Unreachable today (this route hangs off the officer home), but a
        // silent orphan is a worse failure than a refusal, so refuse.
        if (state.identityLoaded && state.myAgentId == null) {
            _uiState.update {
                it.copy(error = "This account has no agent profile, so it can't register farmers.")
            }
            return
        }
        if (!state.identityLoaded) {
            _uiState.update { it.copy(error = "Still loading your profile — try again in a moment.") }
            return
        }

        // The phone is optional, but a typed one must be valid HERE. Nothing
        // downstream will check it: the server's validation is deliberately
        // loose (a format 400 would be retried for ever by the sync queue) and
        // no OTP will ever verify a number the farmer did not enter themselves.
        // This is the only moment a human can still fix it.
        val rawPhone = state.farmerPhone.trim()
        val phone = if (rawPhone.isBlank()) null else normalizeKenyanPhone(rawPhone)
        if (rawPhone.isNotBlank() && phone == null) {
            _uiState.update { it.copy(error = "Enter a valid Kenyan number, e.g. 0712 345 678") }
            return
        }

        val acres = state.acres.trim()
        if (acres.isNotBlank() && acres.toDoubleOrNull() == null) {
            _uiState.update { it.copy(error = "Size must be a number, e.g. 0.5") }
            return
        }

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val farmId = farmRepository.createLocalForFarmer(
                    farmerName = farmerName,
                    farmerPhone = phone,
                    // `farms.name` is NOT NULL server-side and a WAO reading a
                    // list of names rarely has a place-name to give, so it falls
                    // back to the person's. That is not the old conflation
                    // Build-1 locked against: `farmerName` now holds the person
                    // authoritatively, so this is a label, not an identity claim.
                    shambaName = state.effectiveShambaName,
                    registeredByAgentId = state.myAgentId,
                    cropType = state.cropType,
                    acres = acres.toDoubleOrNull(),
                )
                // Crop and acreage have no home on the server's Farm — they live
                // on the Field. Creating one here is what actually carries them
                // off the device; the FarmEntity copies are display denorms.
                fieldRepository.createLocal(
                    farmId = farmId,
                    name = state.effectiveShambaName,
                    acres = acres.ifBlank { "0" },
                    cropType = state.cropType,
                )

                // Best-effort push, exactly like OfficerHomeViewModel.endorse():
                // the row is already saved, so a failure here is a sync delay,
                // never a lost registration.
                //
                // Not surfaced as a message. The farmer appears in the directory
                // the moment we pop back — it is a Room Flow — carrying a
                // SyncBadge that says "On phone" or "Synced" (Build-3 §8 felt
                // states). That is the same information, attached to the row it
                // is about, and it stays true after a snackbar would have gone.
                try {
                    farmRepository.pushPending()
                    fieldRepository.pushPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "registration push deferred — row stays pending", e)
                }

                _uiState.update { it.copy(isSaving = false) }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "registration save failed", e)
                _uiState.update {
                    it.copy(isSaving = false, error = e.message ?: "Couldn't save this farmer")
                }
            }
        }
    }

    private companion object {
        const val TAG = "RegisterFarmer"
    }
}
