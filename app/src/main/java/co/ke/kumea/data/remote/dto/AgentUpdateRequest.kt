package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Mirrors the server UpdateAgentDto. role and agentCode are immutable
 * post-create; commission is unrepresentable. updatedAt drives server-side
 * conflict detection (409 on stale).
 */
@Serializable
data class AgentUpdateRequest(
    val region: String? = null,
    val ward: String? = null,
    val linkedUserId: String? = null,
    val endorsedById: String? = null,
    val status: String? = null,
    val updatedAt: String,
)
