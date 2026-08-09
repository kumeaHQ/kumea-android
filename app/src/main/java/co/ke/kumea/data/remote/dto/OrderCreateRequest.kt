package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * unitPrice is a **String** of integer cents on the wire (e.g. "100000"), never
 * a JSON number — the money contract (the server stores BigInt cents and
 * rejects numeric amounts). channel is REQUIRED and one of the server's
 * OrderChannel values (direct|dealer|agent|ngo|msimu).
 *
 * agentId is the commercial attribution (P1-T8) — the selling agent's STABLE
 * UUID, which the device already holds since it created the agent locally. The
 * server rejects an extension_officer. agentCode rides along as display-only;
 * the server ignores it for attribution and re-derives it from agentId, so the
 * device's provisional code never becomes the stored truth.
 * The Long→String conversion lives in OrderRepository, the only place the
 * boundary is crossed.
 */
@Serializable
data class OrderCreateRequest(
    val id: String,
    val farmerId: String,
    val agentId: String? = null,
    val agentCode: String? = null,
    val dealerId: String? = null,
    val sku: String,
    val qty: Int,
    val unitPrice: String,
    val channel: String,
    val paymentStatus: String? = null,
    val date: String,
)
