package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FarmUpdateRequest(
    val name: String? = null,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
    val updatedAt: String,
)
