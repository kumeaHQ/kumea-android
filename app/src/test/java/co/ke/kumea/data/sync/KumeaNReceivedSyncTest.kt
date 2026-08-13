package co.ke.kumea.data.sync

import co.ke.kumea.data.local.KumeaNReceivedDao
import co.ke.kumea.data.local.KumeaNReceivedEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.KumeaNReceivedCreateRequest
import co.ke.kumea.data.remote.dto.KumeaNReceivedResponse
import co.ke.kumea.data.repository.KumeaNReceivedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The Kumea N handover shim's sync path (KWAP-03 §7), now that it is bound into
 * `Set<SyncableRepository>` and therefore runs on every device.
 *
 * The two failure modes worth pinning:
 *
 *  ① 403 IS TERMINAL. `pushPending()` clears `pendingSync` on a refusal and
 *     audits the payload first. Left pending, a permanently-refused row sits at
 *     the head of the offline queue and is re-sent on every cycle for ever —
 *     the shape this project has shipped three times.
 *  ② THE REQUEST CARRIES NO KEY THE SERVER WOULD REJECT, and in particular no
 *     `recordedByAgentId`: it is on the entity (so the KWAP-02 backfill can
 *     always attribute a handover) but not on the wire, because the server
 *     derives it and `forbidNonWhitelisted` would 400 the extra key.
 */
class KumeaNReceivedSyncTest {

    private class FakeDao : KumeaNReceivedDao {
        val rows = linkedMapOf<String, KumeaNReceivedEntity>()
        override fun getActiveByFarm(farmId: String): Flow<List<KumeaNReceivedEntity>> =
            flowOf(rows.values.filter { it.farmId == farmId && it.deletedAt == null })
        override suspend fun getPendingSync(): List<KumeaNReceivedEntity> =
            rows.values.filter { it.pendingSync }
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun upsertAll(records: List<KumeaNReceivedEntity>) {
            records.forEach { rows[it.id] = it }
        }
        override suspend fun upsert(record: KumeaNReceivedEntity) { rows[record.id] = record }
        override suspend fun markSynced(id: String, serverUpdatedAt: String) {
            rows[id]?.let {
                rows[id] = it.copy(pendingSync = false, syncAction = SyncAction.UPDATE, updatedAt = serverUpdatedAt)
            }
        }
        override suspend fun markSyncedDelete(id: String, deletedAt: String) {
            rows[id]?.let { rows[id] = it.copy(pendingSync = false, deletedAt = deletedAt) }
        }
    }

    private class RecordingConflictDao : SyncConflictDao {
        val inserts = mutableListOf<SyncConflictEntity>()
        override suspend fun insert(conflict: SyncConflictEntity) { inserts.add(conflict) }
    }

    private fun serverRow(id: String, farmId: String = "farm-1") = KumeaNReceivedResponse(
        id = id,
        farmId = farmId,
        strainCode = "soybean",
        packSizeG = 150,
        batchNumber = "130826-01-S",
        qty = 3,
        occurredAt = "2026-09-02T09:00:00Z",
        recordedByAgentId = "agent-1",
        createdAt = "2026-09-02T09:00:00Z",
        updatedAt = "2026-09-02T09:00:00Z",
    )

    private suspend fun KumeaNReceivedRepository.recordOne(farmId: String = "farm-1") = createLocal(
        farmId = farmId,
        strainCode = "soybean",
        packSizeG = 150,
        batchNumber = "130826-01-S",
        qty = 3,
        occurredAt = "2026-09-02T09:00:00Z",
        recordedByAgentId = "agent-1",
    )

    // ── ① 403 is terminal ──────────────────────────────────────────────────

    @Test
    fun `a 403 clears pendingSync instead of parking the row for ever`() = runBlocking {
        val dao = FakeDao()
        val conflicts = RecordingConflictDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createKumeaNReceived(
                record: KumeaNReceivedCreateRequest,
            ): Response<KumeaNReceivedResponse> = Response.error(
                403,
                """{"code":"on_behalf_agent_inactive","message":"suspended"}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        }
        val repo = KumeaNReceivedRepository(dao, conflicts, api)
        val id = repo.recordOne()

        val report = repo.pushPending()

        assertEquals(0, report.succeeded)
        assertFalse(
            "403 is terminal — a refused row must leave the queue",
            dao.rows.getValue(id).pendingSync,
        )
        // Audited before it is dropped, so a wrongly-refused handover is
        // recoverable rather than merely gone.
        assertEquals(1, conflicts.inserts.size)
        assertEquals("kumea_n_received", conflicts.inserts.single().entityType)
    }

    @Test
    fun `a 500 leaves the row pending for the next cycle`() = runBlocking {
        val dao = FakeDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createKumeaNReceived(
                record: KumeaNReceivedCreateRequest,
            ): Response<KumeaNReceivedResponse> =
                Response.error(500, "".toResponseBody("application/json".toMediaType()))
        }
        val repo = KumeaNReceivedRepository(dao, RecordingConflictDao(), api)
        val id = repo.recordOne()

        repo.pushPending()

        // The opposite of the 403 case, and the reason they are different
        // branches: a server that is briefly down is not a server that refused.
        assertTrue(dao.rows.getValue(id).pendingSync)
    }

    // ── ② the wire contract ────────────────────────────────────────────────

    @Test
    fun `the create body carries no key the server would reject`() {
        val request = KumeaNReceivedCreateRequest(
            id = "11111111-1111-4111-8111-111111111111",
            farmId = "22222222-2222-4222-8222-222222222222",
            strainCode = "soybean",
            packSizeG = 150,
            batchNumber = "130826-01-S",
            qty = 3,
            occurredAt = "2026-09-02T09:00:00Z",
        )

        val keys = Json { encodeDefaults = false }
            .encodeToJsonElement(KumeaNReceivedCreateRequest.serializer(), request)
            .jsonObject.keys

        assertEquals(
            setOf("id", "farmId", "strainCode", "packSizeG", "batchNumber", "qty", "occurredAt"),
            keys,
        )
        // PROVENANCE IS DERIVED, NOT DECLARED. It is NOT NULL on the entity so
        // the backfill can always attribute a handover, and absent from the wire
        // because the server derives it — sending it would be an unknown key,
        // which is a 400, which is retried for ever.
        assertTrue("recordedByAgentId" !in keys)
        // And never anything commercial. The engine is backdated to 1 June.
        for (commercial in listOf("agentId", "referrerAgentId", "unitPrice", "amountCents")) {
            assertTrue(commercial !in keys)
        }
    }

    @Test
    fun `a successful push marks the row synced at the server's timestamp`() = runBlocking {
        val dao = FakeDao()
        var sent: KumeaNReceivedCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createKumeaNReceived(
                record: KumeaNReceivedCreateRequest,
            ): Response<KumeaNReceivedResponse> {
                sent = record
                return Response.success(serverRow(record.id))
            }
        }
        val repo = KumeaNReceivedRepository(dao, RecordingConflictDao(), api)
        val id = repo.recordOne()

        val report = repo.pushPending()

        assertEquals(1, report.succeeded)
        assertEquals("130826-01-S", sent!!.batchNumber)
        assertFalse(dao.rows.getValue(id).pendingSync)
    }

    // ── the pull ───────────────────────────────────────────────────────────

    @Test
    fun `pullSince skips rows this device has not pushed yet`() = runBlocking {
        val dao = FakeDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getKumeaNReceived(
                since: String?,
                includeDeleted: Boolean,
            ): List<KumeaNReceivedResponse> = dao.rows.keys.map { serverRow(it) }
        }
        val repo = KumeaNReceivedRepository(dao, RecordingConflictDao(), api)
        val id = repo.recordOne()

        val pulled = repo.pullSince()

        // Push gets its turn first, exactly as farms and harvests do. A pull
        // that overwrote a pending local row would discard the handover before
        // the server had ever heard about it.
        assertEquals(0, pulled)
        assertTrue(dao.rows.getValue(id).pendingSync)
    }

    @Test
    fun `pullSince stores the server's derived recorder`() = runBlocking {
        val dao = FakeDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getKumeaNReceived(
                since: String?,
                includeDeleted: Boolean,
            ): List<KumeaNReceivedResponse> = listOf(serverRow("record-1"))
        }
        val repo = KumeaNReceivedRepository(dao, RecordingConflictDao(), api)

        assertEquals(1, repo.pullSince())

        val row = dao.rows.getValue("record-1")
        assertEquals("agent-1", row.recordedByAgentId)
        assertEquals("130826-01-S", row.batchNumber)
        assertEquals(150, row.packSizeG)
        assertFalse(row.pendingSync)
    }

    @Test
    fun `an empty server page is not an error`() = runBlocking {
        val dao = FakeDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getKumeaNReceived(
                since: String?,
                includeDeleted: Boolean,
            ): List<KumeaNReceivedResponse> = emptyList()
        }

        // The farmer-persona case: this repository is in the sync set on every
        // device, and a handset with nothing to fetch must complete quietly.
        // SyncWorker runs the whole set in one try block, so anything thrown
        // here would abort that device's entire cycle.
        assertEquals(0, KumeaNReceivedRepository(dao, RecordingConflictDao(), api).pullSince())
    }
}
