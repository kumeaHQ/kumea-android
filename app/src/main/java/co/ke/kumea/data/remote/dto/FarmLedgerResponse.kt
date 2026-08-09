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
    // Farm-wide cost split (Ticket 2.1). Sums to totalCostCents. Defaulted so an
    // older server that omits it still deserializes cleanly.
    val byCostCategory: List<CostCategoryLineResponse> = emptyList(),
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

/**
 * One cost-category bucket of a P&L rollup (Ticket 2.1). `category` is the
 * CostCategory enum name, or null for the uncategorised bucket. costCents is a
 * String on the wire (the money contract) — parsed to a signed Long in
 * LedgerRepository, never via Double.
 */
@Serializable
data class CostCategoryLineResponse(
    val category: String? = null,
    val costCents: String,
)
