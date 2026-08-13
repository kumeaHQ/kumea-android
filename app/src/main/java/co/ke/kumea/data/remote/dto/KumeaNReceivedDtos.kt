package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * The "Kumea N received" wire contract (KWAP-03 §7).
 *
 * EVERY FIELD HERE MUST EXIST ON THE SERVER'S `CreateKumeaNReceivedDto`. The API
 * runs `ValidationPipe({ forbidNonWhitelisted: true })`, so an unknown key is a
 * 400 — and 400 is retryable in `pushPending()`, which makes one stray field a
 * row parked at the head of the offline queue for ever. This project has shipped
 * that bug three times already, from three different directions.
 *
 * WHAT IS DELIBERATELY ABSENT: `agentId`, `referrerAgentId`, `unitPrice`, any
 * money at all. This is a research distribution to a farmer who was GIVEN the
 * product; the commission engine is live and accrues backdated to 1 June, so a
 * commercial field on this row would be real liability against agents who sold
 * nothing. `recordedByAgentId` is provenance and is derived server-side from the
 * caller, in the same way `registeredByAgentId` is on Farm.
 */
@Serializable
data class KumeaNReceivedCreateRequest(
    val id: String,
    val farmId: String,
    val strainCode: String,
    val packSizeG: Int,
    /** `DDMMYY + GG + S`, e.g. `130826-01-S`. The reconciliation key. */
    val batchNumber: String,
    val qty: Int,
    val occurredAt: String,
)

@Serializable
data class KumeaNReceivedResponse(
    val id: String,
    val farmId: String,
    val strainCode: String,
    val packSizeG: Int,
    val batchNumber: String,
    val qty: Int,
    val occurredAt: String,
    /** Server-derived from the caller — accepted on read, never sent on write. */
    val recordedByAgentId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
