package co.ke.kumea.ui.screen.officer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.ke.kumea.data.location.LocationCapturer
import co.ke.kumea.data.repository.FarmRepository
import co.ke.kumea.data.repository.FieldRepository
import co.ke.kumea.data.repository.PersonaRepository
import co.ke.kumea.domain.model.BaselineInput
import co.ke.kumea.domain.model.CropSelection
import co.ke.kumea.ui.common.LocationCaptureController
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
    val crops: CropSelection = CropSelection(),
    val acres: String = "",
    val baseline: BaselineInput = BaselineInput(),
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
 * and nothing to get wrong. Derive, don't check.
 *
 * It IS now stored (KWAP-03 §4.1) — a reversal of the note that used to sit
 * here, and worth stating plainly rather than quietly editing. The old argument
 * was that a stored copy could disagree with its source. That is true of a TYPED
 * copy; a stamped one can only be out of date, and traceably so. What changed is
 * the requirement: the research needs to group ~395 farms by ward, and doing
 * that through `registeredByAgentId` → `AgentEntity.ward` means every analysis
 * depends on an agent roster that may have been edited since. The ward a farm
 * was registered in is a historical fact about the registration, not a live
 * property of the agent.
 */
@HiltViewModel
class RegisterFarmerViewModel @Inject constructor(
    private val farmRepository: FarmRepository,
    private val fieldRepository: FieldRepository,
    private val personaRepository: PersonaRepository,
    locationCapturer: LocationCapturer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterFarmerUiState())
    val uiState: StateFlow<RegisterFarmerUiState> = _uiState.asStateFlow()

    /**
     * Same controller as the farmer's own flow, so the two capture identically.
     * Note the "I am standing at this shamba now" tick defaults to OFF, which
     * matters most here: an officer registering ten farmers in a day is not
     * standing on ten shambas, and that is the normal case rather than the edge
     * one (KWAP-03 §5.1⑤).
     */
    val location = LocationCaptureController(locationCapturer, viewModelScope)

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

    /** Cycles nothing → growing → would-like-to-grow → nothing. */
    fun onCropCycle(crop: String) = _uiState.update { state ->
        val crops = state.crops
        val next = when (crop) {
            in crops.growing -> CropSelection(crops.growing - crop, crops.interested + crop)
            in crops.interested -> CropSelection(crops.growing, crops.interested - crop)
            else -> CropSelection(crops.growing + crop, crops.interested - crop)
        }
        state.copy(crops = next)
    }

    fun onBaselineQtyChange(value: String) = _uiState.update {
        it.copy(baseline = it.baseline.copy(qty = value))
    }

    fun onBaselineUnitChange(unit: String) = _uiState.update { state ->
        val next = if (state.baseline.unit == unit) {
            state.baseline.copy(unit = null, bagSizeCenti = null)
        } else {
            state.baseline.copy(unit = unit, bagSizeCenti = null)
        }
        state.copy(baseline = next)
    }

    fun onBaselineBagSizeChange(centi: Long) = _uiState.update {
        it.copy(baseline = it.baseline.copy(bagSizeCenti = centi))
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
                    acres = acres.toDoubleOrNull(),
                    crops = state.crops,
                    location = location.captured(),
                    // Stamped from the officer's own record, which is the only
                    // place it can come from. There is no ward input on this
                    // screen and there must never be one.
                    ward = state.ward,
                    baseline = state.baseline.toBaseline(fallbackCrop = state.crops.primaryGrowing),
                )
                // Crop and acreage have no home on the server's Farm — they live
                // on the Field. Creating one here is what actually carries them
                // off the device; the FarmEntity copies are display denorms.
                fieldRepository.createLocal(
                    farmId = farmId,
                    name = state.effectiveShambaName,
                    acres = acres.ifBlank { "0" },
                    cropType = state.crops.primaryGrowing,
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
