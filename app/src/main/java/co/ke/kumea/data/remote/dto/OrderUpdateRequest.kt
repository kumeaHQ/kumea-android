package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * PATCH body for /orders/:id. updatedAt drives the server's conflict detection
 * (stale → 409, server wins). unitPrice stays a String of cents on the wire.
 * farmerId is absent by design — an order never moves to another farmer.
 */
@Serializable
data class OrderUpdateRequest(
    // P1-T8: attribution is by the agent's stable UUID; agentCode is display-only
    // and re-derived server-side from agentId.
    val agentId: String? = null,
    val agentCode: String? = null,
    val dealerId: String? = null,
    val sku: String? = null,
    val qty: Int? = null,
    val unitPrice: String? = null,
    val channel: String? = null,
    val paymentStatus: String? = null,
    val date: String? = null,
    val updatedAt: String,
)
