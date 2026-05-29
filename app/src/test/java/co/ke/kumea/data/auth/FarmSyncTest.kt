package co.ke.kumea.data.sync

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.repository.FarmRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FarmSyncTest {

    private val farmDao = mockk<FarmDao>(relaxed = true)
    private val syncConflictDao = mockk<SyncConflictDao>(relaxed = true)
    private val api = mockk<KumeaApi>()
    private val repository = FarmRepository(farmDao, syncConflictDao, api)

    @Test
    fun `test offline create workflow`() = runBlocking {
        // 1. Arrange: Create a farm locally
        val farmId = repository.createLocal("Test Farm", 0.0, 0.0, "Rain")

        // 2. Assert: Verify the DAO was called with pendingSync = true
        val slot = slot<FarmEntity>()
        verify { farmDao.upsert(capture(slot)) }
        assertEquals(true, slot.captured.pendingSync)
        assertEquals(SyncAction.CREATE, slot.captured.syncAction)

        println("Logic test passed: Farm created locally with pendingSync=true")
    }
}