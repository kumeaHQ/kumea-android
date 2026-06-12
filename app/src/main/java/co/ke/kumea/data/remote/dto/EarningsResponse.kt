package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * The read-only earnings surface (GET /commission/me, P1-T6/T7). INERT in
 * Phase 1: rates ship unset so [amountAccruedCents] is always "0" while
 * [sachetsAttributed] still counts the agent's real sales — that pair is the
 * gate ("KES 0.00, N sachets", not broken).
 *
 * MONEY: amountAccruedCents / amountSettledCents arrive as JSON **strings** of
 * integer cents and are parsed to Long ONLY in CommissionRepository — never via
 * Double, so a value above 2^53 cents survives byte-for-byte (it never touches
 * IEEE-754). The server returns 200 with a null body for a plain farmer or an
 * extension_officer (no earnings construct at all); Retrofit surfaces that as a
 * null response body, never as this object.
 */
@Serializable
data class EarningsResponse(
    val agentId: String,
    val agentCode: String,
    val role: String,
    val period: String,
    val sachetsAttributed: Int,
    val amountAccruedCents: String,
    val amountSettledCents: String? = null,
)
