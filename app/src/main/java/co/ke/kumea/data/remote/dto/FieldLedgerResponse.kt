package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Read-only P&L rollup for a single field (Ticket 3.3) —
 * GET /fields/{fieldId}/ledger. Same money rules as FarmLedgerResponse: cents
 * are Strings on the wire, parsed to a signed Long (never Double) in the
 * repository. No byField array — just this field's own totals.
 */
@Serializable
data class FieldLedgerResponse(
    val fieldId: String,
    val fieldName: String,
    val currency: String,
    val totalRevenueCents: String,
    val totalCostCents: String,
    val netCents: String,
)
