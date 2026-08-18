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
    /**
     * Where this note came from (KWAP-03-V2 §2.5). `sourceType` is a
     * [co.ke.kumea.data.local.NoteSource] constant, `sourceId` the row that
     * generated it — a seed Purchase carries `"planting"` + the planting's id.
     *
     * ON THE WIRE SINCE 18 AUG. `CreateNoteDto` whitelists both and the service
     * stores them (`kumea-api` `7cb03d2`, deployed). Until then they were
     * device-only: `forbidNonWhitelisted: true` made either key a 400, and the
     * client retried 400 for ever.
     *
     * Both null for a note a human typed. Set together or not at all — one
     * without the other is meaningless, and the server validates `sourceId` as
     * a UUID v4.
     */
    val sourceType: String? = null,
    val sourceId: String? = null,
)
