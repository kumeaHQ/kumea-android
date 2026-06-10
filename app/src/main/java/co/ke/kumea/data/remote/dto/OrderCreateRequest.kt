package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * unitPrice is a **String** of integer cents on the wire (e.g. "100000"), never
 * a JSON number — the money contract (the server stores BigInt cents and
 * rejects numeric amounts). channel is REQUIRED and one of the server's
 * OrderChannel values (direct|dealer|agent|ngo|msimu). agentCode is the
 * commercial attribution — the server rejects an extension_officer's code.
 * The Long→String conversion lives in OrderRepository, the only place the
 * boundary is crossed.
 */
@Serializable
data class OrderCreateRequest(
    val id: String,
    val farmerId: String,
    val agentCode: String? = null,
    val dealerId: String? = null,
    val sku: String,
    val qty: Int,
    val unitPrice: String,
    val channel: String,
    val paymentStatus: String? = null,
    val date: String,
)
