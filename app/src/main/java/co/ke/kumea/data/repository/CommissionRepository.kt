package co.ke.kumea.data.repository

import co.ke.kumea.data.remote.KumeaApi
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in agent's earnings surface (P1-T7), money in Long cents.
 *
 * INERT in Phase 1: [amountAccruedCents] is always 0 (rates unset) while
 * [sachetsAttributed] counts real sales. [ratesActive] is false whenever nothing
 * has accrued — the UI reads it to show "KES 0.00 (commission rates not yet
 * active)" so a zero reads as gated, not broken.
 */
data class EarningsSurface(
    val agentCode: String,
    val period: String,
    val sachetsAttributed: Int,
    val amountAccruedCents: Long,
    val amountSettledCents: Long?,
) {
    /** Phase-1 gate: zero accrued ⇒ rates aren't switched on yet. */
    val ratesActive: Boolean get() = amountAccruedCents != 0L
}

/**
 * Wraps GET /commission/me. The ONLY place commission cents cross the String↔Long
 * boundary — parsed with toLong(), never Double, so a value above 2^53 cents
 * survives. Read-only: the device never writes the ledger.
 */
@Singleton
class CommissionRepository @Inject constructor(
    private val api: KumeaApi,
) {
    /**
     * The earnings surface for [period] ("YYYY-MM"; null = current month), or null
     * when the server reports no earnings construct (200 + null body — a farmer or
     * an extension_officer). Throws on a non-2xx or network failure so the caller
     * surfaces it; nothing is swallowed.
     */
    suspend fun myEarnings(period: String? = null): EarningsSurface? {
        val response = api.getMyEarnings(period)
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: return null
        return EarningsSurface(
            agentCode = body.agentCode,
            period = body.period,
            sachetsAttributed = body.sachetsAttributed,
            // Wire String of integer cents → Long, here and only here.
            amountAccruedCents = body.amountAccruedCents.toLong(),
            amountSettledCents = body.amountSettledCents?.toLong(),
        )
    }
}
