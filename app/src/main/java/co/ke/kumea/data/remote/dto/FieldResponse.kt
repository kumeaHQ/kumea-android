package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * acres arrives as a JSON string ("2.5000") and stays a String all the way into
 * Room — never parsed to Double. The server may also include a nested `farm`
 * object on list/detail responses; ignoreUnknownKeys lets us drop it safely.
 */
@Serializable
data class FieldResponse(
    val id: String,
    val farmId: String,
    val name: String,
    val acres: String,
    val cropType: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
