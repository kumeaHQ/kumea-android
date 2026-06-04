package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Read-only P&L rollup for a farm (Ticket 3.3) — GET /farms/{farmId}/ledger.
 *
 * Every cents value arrives as a JSON **String** (the money contract — server
 * cents are BigInt, never a JSON number) and is parsed to a signed Long only
 * inside LedgerRepository, never via Double. netCents may be negative, so its
 * string can start with '-' (e.g. "-120000"). The sign is already derived
 * server-side; the client never re-derives it.
 */
@Serializable
data class FarmLedgerResponse(
    val farmId: String,
    val currency: String,
    val totalRevenueCents: String,
    val totalCostCents: String,
    val netCents: String,
    val byField: List<FieldLedgerLineResponse> = emptyList(),
)

/** One field's line in a farm rollup. Cents are Strings — see FarmLedgerResponse. */
@Serializable
data class FieldLedgerLineResponse(
    val fieldId: String,
    val fieldName: String,
    val revenueCents: String,
    val costCents: String,
    val netCents: String,
)
