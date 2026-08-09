package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class NoteUpdateRequest(
    val type: String? = null,
    val body: String? = null,
    val amountCents: String? = null,
    // Optional cost label (Ticket 2.1) — the CostCategory enum name, or null.
    val costCategory: String? = null,
    val occurredAt: String? = null,
    val updatedAt: String,
)
