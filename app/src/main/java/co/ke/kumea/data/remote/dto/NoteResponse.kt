package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * amountCents arrives as a JSON **string** ("200000") and is parsed to Long only
 * inside NoteRepository — never via Double. A value above 2^53 survives because
 * it never touches IEEE-754. The server may include a nested `field` object on
 * list/detail responses; ignoreUnknownKeys drops it safely.
 */
@Serializable
data class NoteResponse(
    val id: String,
    val fieldId: String,
    val type: String,
    val body: String,
    val amountCents: String? = null,
    val occurredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
