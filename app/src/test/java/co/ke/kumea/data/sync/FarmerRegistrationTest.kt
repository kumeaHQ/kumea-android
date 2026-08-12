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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Farmer registration — KWAP-01 step 4.
 *
 * The three things here that are genuinely load-bearing, and why each is a test
 * rather than a comment:
 *
 *  ① `pullSince()` must map the ownership/identity columns. Unmapped, they are
 *     nulled locally on every pull, the officer's directory quietly empties
 *     itself, and it looks like a server fault (KWAP-01 §4.2③).
 *  ② `FarmCreateRequest` must carry ONLY keys the server whitelists. An extra
 *     key is a 400, and 400 is retried for ever — a poisoned sync queue.
 *  ③ `referrerAgentId` must stay null on a registration. It is what commission
 *     accrual reads, and the engine is backdated to 1 June.
 */
class FarmerRegistrationTest {

    private class FakeFarmDao : FarmDao {
        val rows = linkedMapOf<String, FarmEntity>()
        override fun getAllActive(): Flow<List<FarmEntity>> = flowOf(rows.values.toList())
        override fun getRegisteredBy(agentId: String): Flow<List<FarmEntity>> =
            flowOf(rows.values.filter { it.registeredByAgentId == agentId && it.deletedAt == null })
        override suspend fun getById(id: String): FarmEntity? = rows[id]
        override suspend fun getAllIds(): List<String> = rows.keys.toList()
        override suspend fun getPendingSync(): List<FarmEntity> = rows.values.filter { it.pendingSync }
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun getByIds(ids: List<String>): List<FarmEntity> =
            ids.mapNotNull { rows[it] }
        override suspend fun upsertAll(farms: List<FarmEntity>) { farms.forEach { rows[it.id] = it } }
        override suspend fun upsert(farm: FarmEntity) { rows[farm.id] = farm }
        override suspend fun markSynced(farmId: String, serverUpdatedAt: String) {
            rows[farmId]?.let {
                rows[farmId] = it.copy(
                    pendingSync = false,
                    syncAction = SyncAction.UPDATE,
                    updatedAt = serverUpdatedAt,
                )
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

    private fun serverFarm(
        id: String,
        userId: String? = "owner-user",
        registeredByAgentId: String? = "agent-1",
        farmerName: String? = "Sila Serem",
        farmerPhone: String? = "+254712345678",
    ) = FarmResponse(
        id = id,
        name = "Sigona",
        userId = userId,
        registeredByAgentId = registeredByAgentId,
        farmerName = farmerName,
        farmerPhone = farmerPhone,
        createdAt = "2026-08-12T09:00:00Z",
        updatedAt = "2026-08-12T09:00:00Z",
    )

    // ── ① the §4.2③ mapping ────────────────────────────────────────────────

    @Test
    fun `pullSince maps ownership and identity instead of nulling them`() = runBlocking {
        val farmDao = FakeFarmDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getFarms(
                since: String?,
                includeDeleted: Boolean,
                registeredBy: String?,
            ): List<FarmResponse> = listOf(serverFarm("farm-1"))
        }

        FarmRepository(farmDao, RecordingConflictDao(), api).pullSince()

        val row = farmDao.rows.getValue("farm-1")
        assertEquals("agent-1", row.registeredByAgentId)
        assertEquals("owner-user", row.farmerUserId)
        assertEquals("Sila Serem", row.farmerName)
        assertEquals("+254712345678", row.farmerPhone)
    }

    @Test
    fun `pullSince keeps the columns the server does not have`() = runBlocking {
        val farmDao = FakeFarmDao()
        // Crop and acreage live on the Field server-side, and useGps is pure UI
        // state, so a pull that rebuilt the row field-by-field would blank all
        // three — invisibly, until a farmer noticed their crop chip had emptied.
        farmDao.rows["farm-1"] = FarmEntity(
            id = "farm-1", name = "Sigona", cropType = "beans", acres = 1.5,
            locationLat = null, locationLng = null, useGps = true, waterSource = null,
            createdAt = "2026-08-01T00:00:00Z", updatedAt = "2026-08-01T00:00:00Z",
            deletedAt = null, pendingSync = false, syncAction = SyncAction.UPDATE,
        )
        val api = object : FakeKumeaApi() {
            override suspend fun getFarms(
                since: String?,
                includeDeleted: Boolean,
                registeredBy: String?,
            ): List<FarmResponse> = listOf(serverFarm("farm-1"))
        }

        FarmRepository(farmDao, RecordingConflictDao(), api).pullSince()

        val row = farmDao.rows.getValue("farm-1")
        assertEquals("beans", row.cropType)
        assertEquals(1.5, row.acres!!, 0.0)
        assertTrue(row.useGps)
    }

    @Test
    fun `pullRegisteredByMe asks for the registrations list, not the owned one`() = runBlocking {
        val farmDao = FakeFarmDao()
        var seenRegisteredBy: String? = "not-called"
        val api = object : FakeKumeaApi() {
            override suspend fun getFarms(
                since: String?,
                includeDeleted: Boolean,
                registeredBy: String?,
            ): List<FarmResponse> {
                seenRegisteredBy = registeredBy
                return listOf(serverFarm("farm-1"))
            }
        }

        val repository = FarmRepository(farmDao, RecordingConflictDao(), api)
        assertEquals(1, repository.pullRegisteredByMe())

        // `me` is the only value the server accepts — anything else is a 400.
        assertEquals("me", seenRegisteredBy)
        assertEquals(
            listOf("farm-1"),
            repository.getRegisteredBy("agent-1").first().map { it.id },
        )
    }

    // ── ② the wire contract ────────────────────────────────────────────────

    @Test
    fun `the create body carries no key the server would reject`() {
        // The server runs forbidNonWhitelisted, so an unknown key is a 400 —
        // and pushPending retries 400. cropType, acres and useGps used to be on
        // this DTO, which meant a farm synced fine until someone picked a crop.
        val serverWhitelist = setOf(
            "id", "name", "locationLat", "locationLng", "waterSource",
            "referrerAgentId", "farmerUserId", "registeredByAgentId",
            "farmerName", "farmerPhone",
        )
        val body = Json.encodeToJsonElement(
            FarmCreateRequest.serializer(),
            FarmCreateRequest(
                id = "farm-1",
                name = "Sigona",
                locationLat = 1.0,
                locationLng = 2.0,
                waterSource = "rain",
                referrerAgentId = null,
                farmerUserId = null,
                farmerName = "Sila Serem",
                farmerPhone = "+254712345678",
            ),
        ).jsonObject

        val unknown = body.keys - serverWhitelist
        assertTrue("Keys the server would 400 on: $unknown", unknown.isEmpty())
    }

    @Test
    fun `the person reaches the wire`() = runBlocking {
        val farmDao = FakeFarmDao()
        var sent: FarmCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createFarm(farm: FarmCreateRequest): Response<FarmResponse> {
                sent = farm
                return Response.success(serverFarm(farm.id))
            }
        }
        val repository = FarmRepository(farmDao, RecordingConflictDao(), api)
        repository.createLocalForFarmer(
            farmerName = "Sila Serem",
            farmerPhone = "+254712345678",
            shambaName = "Sigona",
            registeredByAgentId = "agent-1",
            cropType = "beans",
            acres = 0.5,
        )

        val report = repository.pushPending()

        assertEquals(1, report.succeeded)
        assertEquals("Sila Serem", sent!!.farmerName)
        assertEquals("+254712345678", sent!!.farmerPhone)
    }

    // ── ③ the money boundary ───────────────────────────────────────────────

    @Test
    fun `a registration sets provenance and never sets the referrer`() = runBlocking {
        val farmDao = FakeFarmDao()
        val repository = FarmRepository(farmDao, RecordingConflictDao(), FakeKumeaApi())

        val id = repository.createLocalForFarmer(
            farmerName = "Sila Serem",
            farmerPhone = null,
            shambaName = "Sigona",
            registeredByAgentId = "officer-agent-1",
        )

        val row = farmDao.rows.getValue(id)
        // registered_by records the data entry; referrer is what accrual reads,
        // and the engine has been live since 26 Jun, effective 1 Jun. ~395 KWAP
        // farmers get free product and generate no commission — KWAP-01 §6.
        assertEquals("officer-agent-1", row.registeredByAgentId)
        assertNull(row.referrerAgentId)
        // Left null this season: KWAP-STEP2-DECISIONS §2 deferred creating Users
        // for KWAP farmers, so the server keeps the caller as owner.
        assertNull(row.farmerUserId)
        assertTrue(row.pendingSync)
        assertEquals(SyncAction.CREATE, row.syncAction)
    }

    @Test
    fun `a saved registration is in the register before it has synced`() = runBlocking {
        val farmDao = FakeFarmDao()
        val repository = FarmRepository(farmDao, RecordingConflictDao(), FakeKumeaApi())

        repository.createLocalForFarmer(
            farmerName = "Mercy Jepkoech",
            farmerPhone = null,
            shambaName = "Kaptumo",
            registeredByAgentId = "officer-agent-1",
        )

        // The whole offline-first point: a WAO registering farmers on a bus with
        // no signal sees them immediately, stamped locally with the same agent id
        // the server will derive.
        val register = repository.getRegisteredBy("officer-agent-1").first()
        assertEquals(listOf("Mercy Jepkoech"), register.map { it.farmerName })
    }

    // ── 403 is terminal ────────────────────────────────────────────────────

    @Test
    fun `a 403 is terminal — audited, dropped, never retried`() = runBlocking {
        val farmDao = FakeFarmDao()
        val conflicts = RecordingConflictDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createFarm(farm: FarmCreateRequest): Response<FarmResponse> =
                Response.error(
                    403,
                    """{"code":"on_behalf_ward_mismatch","message":"You may only register farmers in your own ward."}"""
                        .toResponseBody("application/json".toMediaType()),
                )
        }
        val repository = FarmRepository(farmDao, conflicts, api)
        val id = repository.createLocalForFarmer(
            farmerName = "Wrong Ward",
            farmerPhone = null,
            shambaName = "Elsewhere",
            registeredByAgentId = "officer-agent-1",
        )

        val report = repository.pushPending()

        // The server answers 403 rather than 400 for the role and ward
        // rejections precisely so this branch exists. Left pending, the row
        // would be re-sent on every sync cycle for ever.
        assertEquals(1, report.failed)
        assertFalse(farmDao.rows.getValue(id).pendingSync)
        assertEquals(1, conflicts.inserts.size)
        assertEquals("create_403", conflicts.inserts.single().conflictType)
    }
}
