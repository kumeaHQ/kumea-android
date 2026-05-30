package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class FieldUpdateRequest(
    val name: String? = null,
    val acres: String? = null,
    val cropType: String? = null,
    val updatedAt: String,
)
