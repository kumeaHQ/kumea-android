package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FarmCreateRequest(
    val id: String,
    val name: String,
    val cropType: String? = null,
    val acres: Double? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val useGps: Boolean = false,
    val waterSource: String? = null,
    val referrerAgentId: String? = null,
)
