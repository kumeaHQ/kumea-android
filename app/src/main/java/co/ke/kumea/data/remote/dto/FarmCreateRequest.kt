package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * POST /farms body.
 *
 * EVERY FIELD HERE MUST EXIST ON THE SERVER'S `CreateFarmDto`. The API runs
 * `ValidationPipe({ forbidNonWhitelisted: true })`, so an unknown key is not
 * ignored — it is a 400. And `FarmRepository.pushPending()` treats 400 as
 * retryable (only 403 and 409 are terminal), so one unknown key is a row stuck
 * at the head of the offline queue for ever, re-sent on every sync cycle.
 *
 * That is not hypothetical: `cropType`, `acres` and `useGps` used to be here.
 * kotlinx.serialization omits a property that still holds its default, so a farm
 * saved with only a name synced fine and the bug stayed invisible — but the
 * moment a farmer picked a crop chip, typed an acreage or tapped "use my
 * location", the request grew a key the server rejected and that farm never
 * synced again. Removed in KWAP-01 step 4.
 *
 * Crop and acreage were never lost by removing them: they have a real server
 * home on the Field, and `FarmDetailViewModel` already creates one alongside the
 * farm. `useGps` is pure UI state and belongs only in Room.
 */
@Serializable
data class FarmCreateRequest(
    val id: String,
    /** The SHAMBA's name. The person is [farmerName]. */
    val name: String,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
    /**
     * WHO GETS PAID. Null for every officer-created registration — KWAP-01 §6.
     * The commission engine is live and backdated to 1 June, so a value here
     * against a free-product research farmer accrues real money to an agent who
     * did nothing. Provenance is [registeredByAgentId]'s job, and the server
     * derives that one itself.
     */
    val referrerAgentId: String? = null,
    /**
     * WHO THE FARM IS FOR. Left null all this season — KWAP-STEP2-DECISIONS §2
     * deferred creating Users for KWAP farmers, so there is no id to send and
     * the server keeps the caller as owner. Sending someone else's id is the
     * on-behalf path and requires an active officer/agent in the same ward.
     */
    val farmerUserId: String? = null,
    /** WHO THE FARM IS ABOUT. See FarmEntity for why the person lives here. */
    val farmerName: String? = null,
    val farmerPhone: String? = null,
)
