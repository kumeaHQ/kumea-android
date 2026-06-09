package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Mirrors the server Agent row. commissionRuleId is intentionally NOT mapped:
 * the device never reads or writes commission (officers earn zero; rates are
 * gated T6 work). agentCode arrives server-generated (T2).
 */
@Serializable
data class AgentResponse(
    val id: String,
    val role: String,
    val agentCode: String,
    val region: String,
    val ward: String? = null,
    val linkedContactId: String? = null,
    val linkedUserId: String? = null,
    val endorsedById: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
