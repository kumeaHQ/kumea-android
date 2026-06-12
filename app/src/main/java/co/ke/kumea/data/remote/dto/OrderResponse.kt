package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * unitPrice arrives as a JSON **string** ("100000") and is parsed to Long only
 * inside OrderRepository — never via Double. A value above 2^53 survives
 * because it never touches IEEE-754.
 */
@Serializable
data class OrderResponse(
    val id: String,
    val farmerId: String,
    // P1-T8: the authoritative attribution (stable Agent UUID). agentCode is the
    // server-derived display denorm that follows it.
    val agentId: String? = null,
    val agentCode: String? = null,
    val dealerId: String? = null,
    val sku: String,
    val qty: Int,
    val unitPrice: String,
    val channel: String,
    val paymentStatus: String,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
