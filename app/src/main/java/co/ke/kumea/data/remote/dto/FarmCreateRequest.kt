package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FarmCreateRequest(
    val id: String,
    val name: String,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val waterSource: String? = null,
)
