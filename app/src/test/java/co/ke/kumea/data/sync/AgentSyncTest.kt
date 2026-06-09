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
 * Agent offline-sync tests (Phase 1a · T5-slice), mockk-free with hand fakes —
 * the project has no mocking library on the test classpath. Mirrors FarmSyncTest
 * and adds the two Agent-specific guarantees:
 *   • an offline onboard lands as a pending CREATE,
 *   • the CREATE push adopts the server-generated agentCode (the device sends a
 *     blank code; the server returns the canonical VA-NANDI-001 form),
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
    fun `offline onboard marks the row pending CREATE`() = runBlocking {
        val dao = FakeAgentDao()
        val repo = AgentRepository(dao, NoOpConflictDao(), FakeKumeaApi())

        val id = repo.createLocal(role = "village_agent", region = "Nandi", endorsedById = "officer-1")

        val captured = dao.rows.getValue(id)
        assertTrue(captured.pendingSync)
        assertEquals(SyncAction.CREATE, captured.syncAction)
        assertEquals("officer-1", captured.endorsedById)
        // The device sends a blank code; the server owns code generation.
        assertEquals("", captured.agentCode)
    }

    @Test
    fun `CREATE push adopts the server-generated agentCode`() = runBlocking {
        val dao = FakeAgentDao()
        var sentBlankCode = false
        val api = object : FakeKumeaApi() {
            override suspend fun createAgent(agent: AgentCreateRequest): Response<AgentResponse> {
                // The wire request carries no commission field by construction,
                // and no agentCode — the server generates it.
                sentBlankCode = true
                return Response.success(serverAgent(agent.id, "VA-NANDI-001", "2026-06-09T10:00:00Z"))
            }
        }
        val repo = AgentRepository(dao, NoOpConflictDao(), api)
        val id = repo.createLocal(role = "village_agent", region = "Nandi")
        dao.pending = listOf(dao.rows.getValue(id))

        val pushed = repo.pushPending()

        assertEquals(1, pushed)
        assertTrue(sentBlankCode)
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
