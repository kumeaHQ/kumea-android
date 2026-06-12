package co.ke.kumea.data.repository

import co.ke.kumea.data.local.AgentDao
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.AgentCreateRequest
import co.ke.kumea.data.remote.dto.AgentUpdateRequest
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncableRepository
import co.ke.kumea.util.AgentCode
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first Agent sync (Phase 1a · T5-slice) — a direct copy of
 * FarmRepository. The only substantive differences from Farm are the expected
 * ones: the /agents API surface, the Agent fields, and Agent/Agents naming.
 * Everything else (pending push, push-before-pull, 409 → server-wins + audit,
 * pending-skip pull invariant) is mechanically identical.
 *
 * FK ORDERING: Agent is a root in the distribution graph — anything that
 * attributes to an agent (Farm.referrerAgentId) must sync AFTER it. This repo is
 * therefore bound FIRST in RepositoryModule's Set, so the SyncWorker pushes a
 * newly-onboarded agent to the server before it pushes a farmer registered with
 * that agent as referrer (the server FK requires the Agent row to exist first).
 *
 * THE OFFICER ALLOW-LIST is upheld by construction: the device model carries no
 * commission field, so onboarding can never attach commission to any agent,
 * officer or otherwise. The server re-enforces it (DB CHECK + service guard).
 */
@Singleton
class AgentRepository @Inject constructor(
    private val agentDao: AgentDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {

    /** Observe all active agents (live, via Room Flow). */
    fun getAllActive(): Flow<List<AgentEntity>> = agentDao.getAllActive()

    /** Observe active agents of a role — e.g. officers for an endorsement picker. */
    fun getActiveByRole(role: String): Flow<List<AgentEntity>> = agentDao.getActiveByRole(role)

    /**
     * The active agent linked to [userId], if any (P1-T7 persona resolution).
     * Reads the local Room cache only — offline-safe; the caller refreshes the
     * roster first when online. Null means "this user is not an agent" (a farmer).
     */
    suspend fun findMyAgent(userId: String): AgentEntity? =
        agentDao.findByLinkedUserId(userId)

    /**
     * Endorse an agent (P1-T7) — set endorsedById and queue an offline-first
     * UPDATE for sync. Unlike [updateLocal] (which only edits a still-pending,
     * locally-created agent), this reads the FULL active table so an officer can
     * endorse a village_agent that was pulled from the server. No-ops if the
     * target isn't present locally. The server enforces that [endorsedById] must
     * reference an extension_officer (assertEndorserIsOfficer) — the device never
     * re-implements that rule.
     */
    suspend fun endorse(agentId: String, endorsedById: String) {
        val agent = agentDao.getActiveById(agentId) ?: return
        if (agent.endorsedById == endorsedById) return
        val now = Clock.System.now().toString()
        agentDao.upsert(
            agent.copy(
                endorsedById = endorsedById,
                updatedAt = now,
                pendingSync = true,
                syncAction = SyncAction.UPDATE,
            ),
        )
    }

    /**
     * Onboard an agent locally (offline-first). Returns the generated UUID.
     *
     * endorsedById, when set, must be an officer — but that rule is enforced
     * server-side (an officer-only endorser check); the client passes it through.
     * There is no commission parameter: the device cannot express it.
     */
    suspend fun createLocal(
        role: String,
        region: String,
        ward: String? = null,
        linkedContactId: String? = null,
        linkedUserId: String? = null,
        endorsedById: String? = null,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        // P1-T5: mint a provisional <PREFIX>-<REGION>-<NNN> code on device so a
        // sale recorded in the same airplane-mode session can attribute to this
        // agent. NNN continues the local sequence for (role, region); the server
        // adopts this exact code on the CREATE push. Provisional-until-sync: see
        // AgentCode for the multi-device collision caveat (zero risk on one device).
        val existingCodes = agentDao.getCodesWithPrefix(AgentCode.codePrefix(role, region))
        val agentCode = AgentCode.format(role, region, AgentCode.nextSeq(role, region, existingCodes))
        val agent = AgentEntity(
            id = id,
            role = role,
            agentCode = agentCode,
            region = region,
            ward = ward,
            linkedContactId = linkedContactId,
            linkedUserId = linkedUserId,
            endorsedById = endorsedById,
            status = "active",
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            pendingSync = true,
            syncAction = SyncAction.CREATE,
        )
        agentDao.upsert(agent)
        return id
    }

    /** Update an agent locally (offline-first). Mirrors FarmRepository.updateLocal. */
    suspend fun updateLocal(
        id: String,
        region: String? = null,
        ward: String? = null,
        status: String? = null,
        endorsedById: String? = null,
    ) {
        val now = Clock.System.now().toString()
        var agent = agentDao.getPendingSync().find { it.id == id } ?: return
        agent = agent.copy(
            region = region ?: agent.region,
            ward = ward ?: agent.ward,
            status = status ?: agent.status,
            endorsedById = endorsedById ?: agent.endorsedById,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.UPDATE,
        )
        agentDao.upsert(agent)
    }

    /** Soft-delete an agent locally (offline-first). */
    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        var agent = agentDao.getPendingSync().find { it.id == id } ?: return
        agent = agent.copy(
            deletedAt = now,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.DELETE,
        )
        agentDao.upsert(agent)
    }

    /**
     * Push all pending local changes to the server. Agent is a root entity (no
     * upstream FK), so there is no deferral here — every non-2xx is surfaced in
     * the PushReport (409 = server-wins + cleared; anything else left pending and
     * reported with its status). A network failure propagates so the worker
     * retries (CancellationException included — never swallowed, 2.0 rule).
     */
    override suspend fun pushPending(): PushReport {
        val pending = agentDao.getPendingSync()
        val report = PushReportBuilder("Agents")
        report.found = pending.size
        for (agent in pending) {
            when (agent.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createAgent(
                        AgentCreateRequest(
                            id = agent.id,
                            role = agent.role,
                            region = agent.region,
                            ward = agent.ward,
                            linkedContactId = agent.linkedContactId,
                            linkedUserId = agent.linkedUserId,
                            endorsedById = agent.endorsedById,
                            // P1-T5: send the client-minted code; the server
                            // adopts it verbatim, so it round-trips unchanged.
                            agentCode = agent.agentCode,
                        ),
                    )
                    if (response.isSuccessful) {
                        // Adopt the server row — it echoes back the same code.
                        val server = response.body()!!
                        agentDao.upsert(
                            agent.copy(
                                agentCode = server.agentCode,
                                status = server.status,
                                updatedAt = server.updatedAt,
                                pendingSync = false,
                                syncAction = SyncAction.UPDATE,
                            ),
                        )
                        report.succeeded()
                    } else if (response.code() == 409) {
                        recordConflict(agent, response.errorBody()?.string() ?: "{}", "create_409")
                        agentDao.upsert(agent.copy(pendingSync = false))
                        report.failed("409")
                    } else {
                        // Left pending; surfaced loudly (e.g. 401 = refresh failed, 5xx).
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.UPDATE -> {
                    val response = api.updateAgent(
                        agent.id,
                        AgentUpdateRequest(
                            region = agent.region,
                            ward = agent.ward,
                            linkedUserId = agent.linkedUserId,
                            endorsedById = agent.endorsedById,
                            status = agent.status,
                            updatedAt = agent.updatedAt,
                        ),
                    )
                    if (response.isSuccessful) {
                        agentDao.markSynced(agent.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else if (response.code() == 409) {
                        recordConflict(agent, response.errorBody()?.string() ?: "{}", "update_409")
                        agentDao.upsert(agent.copy(pendingSync = false))
                        report.failed("409")
                    } else {
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deleteAgent(agent.id)
                    if (response.isSuccessful) {
                        val now = Clock.System.now().toString()
                        agentDao.markSyncedDelete(agent.id, agent.deletedAt ?: now)
                        report.succeeded()
                    } else {
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    /**
     * Pull server changes since the latest local updatedAt.
     *
     * Agent is a root entity, so there is no parent-FK guard to run here; the
     * only invariant (identical to FarmRepository) is that pull must never
     * clobber a row push hasn't reconciled yet. endorsedById is a soft pointer
     * (no Room FK), so an agent whose endorser is not on this device still
     * applies cleanly — the server owns referential integrity.
     */
    override suspend fun pullSince(): Int {
        val since = agentDao.getLatestUpdatedAt()
        val serverAgents = try {
            api.getAgents(since = since, includeDeleted = true)
        } catch (e: Exception) {
            throw e
        }

        if (serverAgents.isEmpty()) return 0

        val localEntities = serverAgents.map { server ->
            AgentEntity(
                id = server.id,
                role = server.role,
                agentCode = server.agentCode,
                region = server.region,
                ward = server.ward,
                linkedContactId = server.linkedContactId,
                linkedUserId = server.linkedUserId,
                endorsedById = server.endorsedById,
                status = server.status,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        val pendingIds = agentDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            agentDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
    }

    private suspend fun recordConflict(local: AgentEntity, serverPayload: String, conflictType: String) {
        val entity = SyncConflictEntity(
            id = UUID.randomUUID().toString(),
            entityType = "agent",
            entityId = local.id,
            localPayload = local.toString(),
            serverPayload = serverPayload,
            conflictType = conflictType,
            occurredAt = Clock.System.now().toString(),
        )
        syncConflictDao.insert(entity)
    }
}
