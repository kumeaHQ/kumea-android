package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * The "Kumea N received" wire contract (KWAP-03 §7).
 *
 * ⚠️ THE SERVER DOES NOT HAVE THIS ROUTE YET. It ships in the KWAP-03 kumea-api
 * patch, which is written but not deployed. Until it is, `KumeaNReceivedRepository`
 * is NOT bound into the `Set<SyncableRepository>` — see the note in
 * `di/RepositoryModule.kt`. A push against a missing route is a 404, and 404 is
 * not terminal client-side, so binding this early would put a row at the head of
 * the offline queue and re-send it every cycle for ever. Exactly the failure
 * this project has already had three times, arrived at from a new direction.
 *
 * EVERY FIELD HERE MUST EXIST ON THE SERVER'S DTO before that binding is added.
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
