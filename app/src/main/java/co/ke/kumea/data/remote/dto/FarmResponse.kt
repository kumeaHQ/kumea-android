package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FarmResponse(
    val id: String,
    val name: String,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
    // T4: who registered this farmer (non-commercial; officers allowed).
    val referrerAgentId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
