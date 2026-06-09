package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FarmCreateRequest(
    val id: String,
    val name: String,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
    // T4: the Agent who REGISTERED this farmer. NON-COMMERCIAL attribution —
    // officers are allowed here (contact-point role), unlike commercial sale
    // attribution. referrer = who registered; agent_code = who sold.
    val referrerAgentId: String? = null,
)
