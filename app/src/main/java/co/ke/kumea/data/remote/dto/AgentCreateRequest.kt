package co.ke.kumea.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Mirrors the server CreateAgentDto (kumea-api). NOTE the absence of any
 * commission field — the wire contract cannot carry commission for an agent, so
 * an officer can never acquire one from the app. role is the server's lowercase
 * enum (village_agent | agro_dealer | extension_officer | cooperative).
 *
 * agentCode is now sent (P1-T5): the device mints a provisional
 * `<PREFIX>-<REGION>-<NNN>` code offline (see [co.ke.kumea.util.AgentCode]) so a
 * sale recorded in the same airplane-mode session can attribute to the agent.
 * The server's CreateAgentDto.agentCode is optional and adopted verbatim, so the
 * client-minted code round-trips unchanged. Omitting it (null) still lets the
 * server generate one — kept optional for compatibility.
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
    val agentCode: String? = null,
)
