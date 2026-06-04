package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * amountCents is a **String** on the wire (e.g. "200000"), never a JSON number —
 * the money contract. The server stores BigInt cents and rejects numeric
 * amounts. Null for an ACTIVITY note with no cost. type is the enum name
 * ("ACTIVITY" | "PURCHASE" | "SALE"). The Long→String conversion lives in
 * NoteRepository, the only place the boundary is crossed.
 */
@Serializable
data class NoteCreateRequest(
    val id: String,
    val fieldId: String,
    val type: String,
    val body: String,
    val amountCents: String? = null,
    // Optional cost label (Ticket 2.1) — the CostCategory enum name, or null.
    val costCategory: String? = null,
    val occurredAt: String,
)
