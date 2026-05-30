package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * acres is a String so kotlinx.serialization emits it as a JSON string
 * ("0.3333"), matching the API contract. Never a Double — no float rounding.
 */
@Serializable
data class FieldCreateRequest(
    val id: String,
    val farmId: String,
    val name: String,
    val acres: String,
    val cropType: String? = null,
)
