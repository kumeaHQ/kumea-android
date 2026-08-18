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
    // See NoteCreateRequest. Sent on UPDATE too because editing a planting's
    // seed cost PATCHes the linked note (§2.5) — omitting them here would be
    // fine (the server keeps the stored value when a key is absent) but sending
    // them keeps push and pull describing the same row.
    val sourceType: String? = null,
    val sourceId: String? = null,
    val updatedAt: String,
)
