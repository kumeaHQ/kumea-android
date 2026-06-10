package co.ke.kumea.data.sync

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.data.repository.FarmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/**
 * Farm offline-sync tests (mockk-free per Ticket 3.2). Covers the offline create
 * landing as a pending CREATE, and the P1-T5 FK guard: a farm whose referrer
 * Agent has not synced yet is DEFERRED (stays pending, retried) rather than
 * failed — the same per-repo guard OrderRepository uses, applied to
 * Farm.referrerAgentId → Agent.
 */
class FarmSyncTest {

    private class FakeFarmDao : FarmDao {
        val rows = mutableMapOf<String, FarmEntity>()
        val upserts = mutableListOf<FarmEntity>()
        override fun getAllActive(): Flow<List<FarmEntity>> = flowOf(rows.values.toList())
        override suspend fun getAllIds(): List<String> = rows.keys.toList()
        override suspend fun getPendingSync(): List<FarmEntity> = rows.values.filter { it.pendingSync }
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun upsertAll(farms: List<FarmEntity>) {
            farms.forEach { rows[it.id] = it; upserts.add(it) }
        }
        override suspend fun upsert(farm: FarmEntity) { rows[farm.id] = farm; upserts.add(farm) }
        override suspend fun markSynced(farmId: String, serverUpdatedAt: String) {
            rows[farmId]?.let {
                rows[farmId] = it.copy(pendingSync = false, syncAction = SyncAction.UPDATE, updatedAt = serverUpdatedAt)
            }
        }
        override suspend fun markSyncedDelete(farmId: String, deletedAt: String) {
            rows[farmId]?.let { rows[farmId] = it.copy(pendingSync = false, deletedAt = deletedAt) }
        }
    }

    private class RecordingConflictDao : SyncConflictDao {
        val inserts = mutableListOf<SyncConflictEntity>()
        override suspend fun insert(conflict: SyncConflictEntity) { inserts.add(conflict) }
    }

    @Test
    fun `offline create marks the row pending`() = runBlocking {
        val farmDao = FakeFarmDao()
        val repository = FarmRepository(farmDao, RecordingConflictDao(), FakeKumeaApi())

        repository.createLocal("Test Farm", 0.0, 0.0, "Rain")

        val captured = farmDao.upserts.single()
        assertEquals(true, captured.pendingSync)
        assertEquals(SyncAction.CREATE, captured.syncAction)
    }

    @Test
    fun `pushPending defers when the referrer agent is not on the server yet`() = runBlocking {
        val farmDao = FakeFarmDao()
        val conflicts = RecordingConflictDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createFarm(farm: FarmCreateRequest): Response<FarmResponse> =
                Response.error(
                    400,
                    """{"code":"referrer_agent_not_found","message":"referrerAgentId must reference an existing agent."}"""
                        .toResponseBody("application/json".toMediaType()),
                )
        }
        val repository = FarmRepository(farmDao, conflicts, api)
        val id = repository.createLocal(
            name = "Demo Farmer", locationLat = null, locationLng = null,
            waterSource = null, referrerAgentId = "agent-not-synced",
        )

        val pushed = repository.pushPending()

        // Deferred: nothing pushed, the row stays PENDING for the next cycle once
        // the referrer agent lands, and it is NOT recorded as a conflict.
        assertEquals(0, pushed)
        assertTrue(farmDao.rows.getValue(id).pendingSync)
        assertTrue(conflicts.inserts.isEmpty())
    }
}
