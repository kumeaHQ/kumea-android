package co.ke.kumea.data.repository

import android.util.Log
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.domain.model.Persona
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of resolving the signed-in user's persona (P1-T7). */
sealed interface PersonaResult {
    /** Persona determined. [agent] is the linked Agent (null for a farmer). */
    data class Resolved(val persona: Persona, val agent: AgentEntity?) : PersonaResult

    /** Hard failure (e.g. offline on first resolve with no cached identity). Surfaced for retry — never a silent default. */
    data class Failed(val message: String) : PersonaResult
}

/**
 * Resolves the signed-in user's persona from their linked Agent.role (P1-T7).
 *
 * Source of truth is the channel-wide /agents roster (Agent.linkedUserId → User),
 * cached in Room — so resolution is offline-capable after the first sync. No
 * /auth/me agent-embed is required: the device already holds the roster every
 * other distribution feature relies on.
 *
 *   1. Fast path — the linked agent is already cached → classify, done (offline).
 *   2. Cold path — no cached link yet → refresh the roster once, re-check. A
 *      refresh failure is logged (never swallowed) and we fall back to FARMER:
 *      "no linked agent" IS the farmer case, and the farmer surface is the safe,
 *      non-commercial default. CancellationException is always re-thrown.
 *
 * A user with no resolvable identity at all (offline, nothing cached) yields
 * [PersonaResult.Failed] so the UI can offer retry rather than guess.
 */
@Singleton
class PersonaRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val agentRepository: AgentRepository,
) {
    suspend fun resolve(): PersonaResult {
        val userId = resolveUserId()
            ?: return PersonaResult.Failed(
                "Couldn't identify your account. Check your connection and try again.",
            )

        agentRepository.findMyAgent(userId)?.let { return resolved(it) }

        // Cold path: refresh the roster so a freshly-onboarded agent's own row
        // lands, then re-check. Failure here is non-fatal — log and treat as farmer.
        try {
            agentRepository.pullSince()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Agent roster refresh failed during persona resolve; treating as farmer", e)
        }

        return agentRepository.findMyAgent(userId)?.let { resolved(it) }
            ?: PersonaResult.Resolved(Persona.FARMER, null)
    }

    /**
     * The signed-in user's linked Agent from the local cache, or null for a
     * farmer. Used by the agent/officer home screens (which only render after a
     * successful [resolve], so the row is already cached → offline-safe).
     */
    suspend fun myAgent(): AgentEntity? {
        val userId = authRepository.currentUserId() ?: return null
        return agentRepository.findMyAgent(userId)
    }

    /** Cached user id, falling back to GET /auth/me when online. Null if neither works. */
    private suspend fun resolveUserId(): String? {
        authRepository.currentUserId()?.let { return it }
        return try {
            authRepository.me().id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET /auth/me failed while resolving user id", e)
            null
        }
    }

    private fun resolved(agent: AgentEntity): PersonaResult.Resolved =
        PersonaResult.Resolved(Persona.fromAgentRole(agent.role), agent)

    private companion object {
        const val TAG = "PersonaRepo"
    }
}
