package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * GET /farms and GET /farms?registeredBy=me — the same shape, different WHERE.
 *
 * Note what is NOT here: `cropType`, `acres` and `useGps`. The server's Farm has
 * never carried them (crop and acreage live on the Field), so they were always
 * arriving null and `pullSince()` was writing that null over good local values
 * on every pull. They are device-only display denorms now, and `pullSince()`
 * preserves them — see FarmEntity.
 *
 * [userId] is the owner as the server sees it, and maps to
 * `FarmEntity.farmerUserId`. The names differ because the server's column
 * predates the question ("whose farm is this?" only became askable in step 2)
 * and the device's name was chosen in step 1 to say what it means.
 */
@Serializable
data class FarmResponse(
    val id: String,
    /** The SHAMBA's name. The person is [farmerName]. */
    val name: String,
    /** Owner. → `FarmEntity.farmerUserId`. Optional so a pre-step-2 server can't crash a pull. */
    val userId: String? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
    /** WHO GETS PAID. Commission attribution — never set by a registration. */
    val referrerAgentId: String? = null,
    /** WHO TYPED IT IN. Server-derived provenance; the officer directory filters on it. */
    val registeredByAgentId: String? = null,
    /** WHO THE FARM IS ABOUT (step 4). Null on every row registered before it existed. */
    val farmerName: String? = null,
    val farmerPhone: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
