package co.ke.kumea.data.sync

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.repository.FarmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Rewritten mockk-free (Ticket 3.2): the project has no mocking library on the
 * test classpath, so this uses a plain fake DAO. Same assertion as before —
 * an offline create lands as a pending CREATE row.
 */
class FarmSyncTest {

    private class FakeFarmDao : FarmDao {
        val upserts = mutableListOf<FarmEntity>()
        override fun getAllActive(): Flow<List<FarmEntity>> = flowOf(emptyList())
        override suspend fun getAllIds(): List<String> = emptyList()
        override suspend fun getPendingSync(): List<FarmEntity> = emptyList()
        override suspend fun getLatestUpdatedAt(): String? = null
        override suspend fun upsertAll(farms: List<FarmEntity>) { upserts.addAll(farms) }
        override suspend fun upsert(farm: FarmEntity) { upserts.add(farm) }
        override suspend fun markSynced(farmId: String, serverUpdatedAt: String) {}
        override suspend fun markSyncedDelete(farmId: String, deletedAt: String) {}
    }

    private class NoOpConflictDao : SyncConflictDao {
        override suspend fun insert(conflict: SyncConflictEntity) {}
    }

    @Test
    fun `offline create marks the row pending`() = runBlocking {
        val farmDao = FakeFarmDao()
        val repository = FarmRepository(farmDao, NoOpConflictDao(), FakeKumeaApi())

        repository.createLocal("Test Farm", 0.0, 0.0, "Rain")

        val captured = farmDao.upserts.single()
        assertEquals(true, captured.pendingSync)
        assertEquals(SyncAction.CREATE, captured.syncAction)
    }
}
