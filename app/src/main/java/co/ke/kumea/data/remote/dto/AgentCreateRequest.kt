package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Mirrors the server CreateAgentDto (kumea-api). NOTE the absence of any
 * commission field — the wire contract cannot carry commission for an agent, so
 * an officer can never acquire one from the app. role is the server's lowercase
 * enum (village_agent | agro_dealer | extension_officer | cooperative).
 * agentCode is omitted: the server generates it deterministically (T2).
 */
@Serializable
data class AgentCreateRequest(
    val id: String,
    val role: String,
    val region: String,
    val ward: String? = null,
    val linkedContactId: String? = null,
    val linkedUserId: String? = null,
    val endorsedById: String? = null,
)
