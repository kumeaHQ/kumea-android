package co.ke.kumea.data.sync

import co.ke.kumea.data.local.AgentDao
import co.ke.kumea.data.local.AgentEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.AgentCreateRequest
import co.ke.kumea.data.remote.dto.AgentResponse
import co.ke.kumea.data.repository.AgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/**
 * Agent offline-sync tests (Phase 1a · T5-slice → P1-T5), mockk-free with hand
 * fakes — the project has no mocking library on the test classpath. Mirrors
 * FarmSyncTest and adds the Agent-specific guarantees:
 *   • an offline onboard lands as a pending CREATE with a client-minted
 *     <PREFIX>-<REGION>-<NNN> code (P1-T5 — so a sale in the same airplane-mode
 *     session can attribute to it),
 *   • the NNN sequence advances per (role, region) across onboards,
 *   • the CREATE push SENDS that minted code and the server adopts it verbatim,
 *   • the push-before-pull invariant: a still-pending row is not clobbered by a
 *     concurrent server pull.
 */
class AgentSyncTest {

    private class FakeAgentDao : AgentDao {
        val rows = mutableMapOf<String, AgentEntity>()
        var pending: List<AgentEntity> = emptyList()
        override fun getAllActive(): Flow<List<AgentEntity>> = flowOf(rows.values.toList())
        override fun getActiveByRole(role: String): Flow<List<AgentEntity>> =
            flowOf(rows.values.filter { it.role == role })
        override suspend fun getAllIds(): List<String> = rows.keys.toList()
        override suspend fun getCodesWithPrefix(prefix: String): List<String> =
            rows.values.map { it.agentCode }.filter { it.startsWith(prefix) }
        override suspend fun findByLinkedUserId(userId: String): AgentEntity? =
            rows.values.firstOrNull { it.linkedUserId == userId && it.deletedAt == null }
        override suspend fun getActiveById(id: String): AgentEntity? =
            rows[id]?.takeIf { it.deletedAt == null }
        override suspend fun getPendingSync(): List<AgentEntity> = pending
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun upsertAll(agents: List<AgentEntity>) {
            agents.forEach { rows[it.id] = it }
        }
        override suspend fun upsert(agent: AgentEntity) { rows[agent.id] = agent }
        override suspend fun markSynced(agentId: String, serverUpdatedAt: String) {
            rows[agentId]?.let { rows[agentId] = it.copy(pendingSync = false, syncAction = SyncAction.UPDATE, updatedAt = serverUpdatedAt) }
        }
        override suspend fun markSyncedDelete(agentId: String, deletedAt: String) {
            rows[agentId]?.let { rows[agentId] = it.copy(pendingSync = false, deletedAt = deletedAt) }
        }
    }

    private class NoOpConflictDao : SyncConflictDao {
        override suspend fun insert(conflict: SyncConflictEntity) {}
    }

    private fun serverAgent(id: String, code: String, updatedAt: String) = AgentResponse(
        id = id,
        role = "village_agent",
        agentCode = code,
        region = "Nandi",
        ward = null,
        linkedContactId = null,
        linkedUserId = null,
        endorsedById = null,
        status = "active",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        deletedAt = null,
    )

    @Test
    fun `offline onboard marks the row pending CREATE with a minted code`() = runBlocking {
        val dao = FakeAgentDao()
        val repo = AgentRepository(dao, NoOpConflictDao(), FakeKumeaApi())

        val id = repo.createLocal(role = "village_agent", region = "Nandi", endorsedById = "officer-1")

        val captured = dao.rows.getValue(id)
        assertTrue(captured.pendingSync)
        assertEquals(SyncAction.CREATE, captured.syncAction)
        assertEquals("officer-1", captured.endorsedById)
        // P1-T5: the device mints a provisional <PREFIX>-<REGION>-<NNN> code so a
        // sale in the same offline session can attribute to this agent.
        assertEquals("VA-NANDI-001", captured.agentCode)
    }

    @Test
    fun `minted NNN advances per role and region`() = runBlocking {
        val dao = FakeAgentDao()
        val repo = AgentRepository(dao, NoOpConflictDao(), FakeKumeaApi())

        val first = repo.createLocal(role = "village_agent", region = "Nandi")
        val second = repo.createLocal(role = "village_agent", region = "Nandi")
        // A different role shares the region but not the prefix → its own sequence.
        val officer = repo.createLocal(role = "extension_officer", region = "Nandi")

        assertEquals("VA-NANDI-001", dao.rows.getValue(first).agentCode)
        assertEquals("VA-NANDI-002", dao.rows.getValue(second).agentCode)
        assertEquals("EO-NANDI-001", dao.rows.getValue(officer).agentCode)
    }

    @Test
    fun `CREATE push sends the client-minted code and the server adopts it`() = runBlocking {
        val dao = FakeAgentDao()
        var sentCode: String? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createAgent(agent: AgentCreateRequest): Response<AgentResponse> {
                // The wire request carries the client-minted code (and no
                // commission field by construction); the server adopts it verbatim.
                sentCode = agent.agentCode
                return Response.success(serverAgent(agent.id, agent.agentCode!!, "2026-06-09T10:00:00Z"))
            }
        }
        val repo = AgentRepository(dao, NoOpConflictDao(), api)
        val id = repo.createLocal(role = "village_agent", region = "Nandi")
        dao.pending = listOf(dao.rows.getValue(id))

        val report = repo.pushPending()

        assertEquals(1, report.succeeded)
        assertEquals("Agents", report.repo)
        assertEquals("VA-NANDI-001", sentCode)
        val synced = dao.rows.getValue(id)
        assertEquals("VA-NANDI-001", synced.agentCode)
        assertFalse(synced.pendingSync)
        assertEquals(SyncAction.UPDATE, synced.syncAction)
    }

    @Test
    fun `pull does not clobber a still-pending row`() = runBlocking {
        val dao = FakeAgentDao()
        val pendingRow = AgentEntity(
            id = "a1", role = "village_agent", agentCode = "", region = "Nandi", ward = null,
            linkedContactId = null, linkedUserId = null, endorsedById = null, status = "active",
            createdAt = "2026-06-09T09:00:00Z", updatedAt = "2026-06-09T09:00:00Z",
            deletedAt = null, pendingSync = true, syncAction = SyncAction.CREATE,
        )
        dao.rows["a1"] = pendingRow
        dao.pending = listOf(pendingRow)

        val api = object : FakeKumeaApi() {
            override suspend fun getAgents(since: String?, includeDeleted: Boolean, role: String?): List<AgentResponse> =
                listOf(serverAgent("a1", "VA-NANDI-009", "2026-06-09T08:00:00Z"))
        }
        val repo = AgentRepository(dao, NoOpConflictDao(), api)

        val applied = repo.pullSince()

        assertEquals(0, applied)
        // The pending local row is untouched — push gets its turn first.
        assertEquals("", dao.rows.getValue("a1").agentCode)
        assertTrue(dao.rows.getValue("a1").pendingSync)
    }
}
