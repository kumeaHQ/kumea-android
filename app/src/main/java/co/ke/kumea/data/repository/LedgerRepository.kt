package co.ke.kumea.data.repository

import co.ke.kumea.data.remote.KumeaApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only P&L rollup (Ticket 3.3). The Ledger is NOT an offline entity — it is
 * a server-computed summary of the Notes already synced (3.2). There is no Room
 * table, no sync, no writes here; this repository just fetches the rollup and
 * parses its money.
 *
 * Money discipline (inherited from 3.2, still law): cents arrive as Strings and
 * are parsed to a **signed Long** with String.toLong() — NEVER via Double. net
 * can be negative; Long is signed so "-120000".toLong() is fine, and a value
 * above 2^53 survives because it never touches IEEE-754. The sign is already
 * derived server-side (the one rollup function) — the client never re-derives it.
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val api: KumeaApi,
) {
    /** Fetch a farm's P&L rollup. Throws on network/HTTP error — caller surfaces it. */
    suspend fun getFarmLedger(farmId: String): FarmLedger {
        val dto = api.getFarmLedger(farmId)
        return FarmLedger(
            farmId = dto.farmId,
            currency = dto.currency,
            revenueCents = dto.totalRevenueCents.toLong(),
            costCents = dto.totalCostCents.toLong(),
            netCents = dto.netCents.toLong(),
            byField = dto.byField.map { line ->
                FieldLedgerLine(
                    fieldId = line.fieldId,
                    fieldName = line.fieldName,
                    revenueCents = line.revenueCents.toLong(),
                    costCents = line.costCents.toLong(),
                    netCents = line.netCents.toLong(),
                )
            },
        )
    }

    /** Fetch a single field's P&L rollup. Throws on network/HTTP error. */
    suspend fun getFieldLedger(fieldId: String): FieldLedger {
        val dto = api.getFieldLedger(fieldId)
        return FieldLedger(
            fieldId = dto.fieldId,
            fieldName = dto.fieldName,
            currency = dto.currency,
            revenueCents = dto.totalRevenueCents.toLong(),
            costCents = dto.totalCostCents.toLong(),
            netCents = dto.netCents.toLong(),
        )
    }
}

/** A farm's rollup with cents as signed Longs (parsed once, here). */
data class FarmLedger(
    val farmId: String,
    val currency: String,
    val revenueCents: Long,
    val costCents: Long,
    val netCents: Long,
    val byField: List<FieldLedgerLine>,
)

data class FieldLedgerLine(
    val fieldId: String,
    val fieldName: String,
    val revenueCents: Long,
    val costCents: Long,
    val netCents: Long,
)

data class FieldLedger(
    val fieldId: String,
    val fieldName: String,
    val currency: String,
    val revenueCents: Long,
    val costCents: Long,
    val netCents: Long,
)
