package co.ke.kumea.data.repository

import co.ke.kumea.data.local.CropStatus
import co.ke.kumea.data.local.FakeFarmCropDao
import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.data.local.LocationSource
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.location.CapturedLocation
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.FarmResponse
import co.ke.kumea.domain.model.BaselineInput
import co.ke.kumea.domain.model.CropSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KWAP-03 farm profile: the crop set, the stamped ward, the recalled
 * baseline, and the location metadata.
 *
 * Every one of these columns is device-only until the matching kumea-api patch
 * deploys, so the pull-preservation test below is not belt-and-braces — it is
 * the only thing standing between a farmer's baseline and a background sync.
 * This project has already lost columns to a naive pull rebuild once
 * (KWAP-01 §4.2③), on this exact entity.
 */
class FarmProfileTest {

    private class FakeFarmDao : FarmDao {
        val rows = linkedMapOf<String, FarmEntity>()
        override fun getAllActive(): Flow<List<FarmEntity>> = flowOf(rows.values.toList())
        override fun getRegisteredBy(agentId: String): Flow<List<FarmEntity>> =
            flowOf(rows.values.filter { it.registeredByAgentId == agentId })
        override suspend fun getById(id: String): FarmEntity? = rows[id]
        override suspend fun getAllIds(): List<String> = rows.keys.toList()
        override suspend fun getPendingSync(): List<FarmEntity> = rows.values.filter { it.pendingSync }
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun getByIds(ids: List<String>): List<FarmEntity> = ids.mapNotNull { rows[it] }
        override suspend fun upsertAll(farms: List<FarmEntity>) { farms.forEach { rows[it.id] = it } }
        override suspend fun upsert(farm: FarmEntity) { rows[farm.id] = farm }
        override suspend fun markSynced(farmId: String, serverUpdatedAt: String) {
            rows[farmId]?.let {
                rows[farmId] = it.copy(pendingSync = false, syncAction = SyncAction.UPDATE, updatedAt = serverUpdatedAt)
            }
        }
        override suspend fun markSyncedDelete(farmId: String, deletedAt: String) {
            rows[farmId]?.let { rows[farmId] = it.copy(pendingSync = false, deletedAt = deletedAt) }
        }
    }

    private class NoopConflictDao : SyncConflictDao {
        override suspend fun insert(conflict: SyncConflictEntity) = Unit
    }

    private fun repository(
        farmDao: FarmDao = FakeFarmDao(),
        cropDao: FakeFarmCropDao = FakeFarmCropDao(),
        api: FakeKumeaApi = FakeKumeaApi(),
    ) = FarmRepository(farmDao, cropDao, NoopConflictDao(), api)

    // ── the crop set ───────────────────────────────────────────────────────

    @Test
    fun `growing and interested are both written, with their statuses`() = runBlocking {
        val cropDao = FakeFarmCropDao()
        val farmId = repository(cropDao = cropDao).createLocal(
            name = "Sigona",
            crops = CropSelection(
                growing = setOf("maize", "beans"),
                interested = setOf("soybean"),
            ),
        )

        val rows = cropDao.getByFarmOnce(farmId).associate { it.crop to it.status }
        assertEquals(
            mapOf(
                "beans" to CropStatus.GROWING,
                "maize" to CropStatus.GROWING,
                // THE SALES SIGNAL. A farmer growing maize who would try soybean
                // is a lead, and nothing else in the system records that.
                "soybean" to CropStatus.INTERESTED,
            ),
            rows,
        )
    }

    @Test
    fun `a crop cannot be growing and interested at once`() = runBlocking {
        val cropDao = FakeFarmCropDao()
        // Both sets naming beans would be two rows with the same composite
        // primary key — one silently overwriting the other.
        val farmId = repository(cropDao = cropDao).createLocal(
            name = "Sigona",
            crops = CropSelection(growing = setOf("beans"), interested = setOf("beans", "lucerne")),
        )

        val rows = cropDao.getByFarmOnce(farmId).associate { it.crop to it.status }
        assertEquals(CropStatus.GROWING, rows["beans"])
        assertEquals(CropStatus.INTERESTED, rows["lucerne"])
        assertEquals(2, rows.size)
    }

    @Test
    fun `the list card denorm comes from the crop set, alphabetically and stably`() = runBlocking {
        val farmDao = FakeFarmDao()
        val farmId = repository(farmDao = farmDao).createLocal(
            name = "Sigona",
            crops = CropSelection(growing = setOf("maize", "beans")),
        )

        // `farms.cropType` feeds "Beans · 3.0 acre · Rain" on the farm list
        // without a join. A set has no insertion order, so the choice has to be
        // deterministic or the card changes between reads for no reason.
        assertEquals("beans", farmDao.rows.getValue(farmId).cropType)
    }

    @Test
    fun `an interested-only farm still has no primary growing crop`() = runBlocking {
        val farmDao = FakeFarmDao()
        val farmId = repository(farmDao = farmDao).createLocal(
            name = "Sigona",
            crops = CropSelection(interested = setOf("soybean")),
        )

        // Honest: they grow nothing we know of. Promoting an aspiration to the
        // card would read as fact on the farm list.
        assertNull(farmDao.rows.getValue(farmId).cropType)
    }

    // ── the baseline ───────────────────────────────────────────────────────

    @Test
    fun `bags convert to canonical kilograms using the size the farmer stated`() {
        // §12: 5 bags @ 90 kg → 450 kg.
        val baseline = BaselineInput(qty = "5", unit = HarvestUnits.BAGS, bagSizeCenti = 9_000)
            .toBaseline()!!

        assertEquals(45_000L, baseline.kgCenti)
        assertEquals(9_000L, baseline.conversionFactorCenti)
    }

    @Test
    fun `bags with no size stated yield nothing at all`() {
        // A bag is 50 or 90 kg — nearly a doubling of the figure the season is
        // judged on. Half an answer must produce no answer, not a plausible one.
        assertNull(BaselineInput(qty = "5", unit = HarvestUnits.BAGS).toBaseline())
    }

    @Test
    fun `gorogoro uses the standard tin without being asked`() {
        val baseline = BaselineInput(qty = "2.5", unit = HarvestUnits.GOROGORO).toBaseline()!!

        // 2.5 × 2 kg = 5 kg. Small enough that being wrong costs little, and the
        // factor is stored on the row either way so it stays re-derivable.
        assertEquals(500L, baseline.kgCenti)
        assertEquals(200L, baseline.conversionFactorCenti)
    }

    @Test
    fun `a blank or half-typed baseline is null, never a zero`() {
        // Skippable by construction. A zero would say "this farm harvested
        // nothing last season", which is a claim, not an absence.
        assertNull(BaselineInput().toBaseline())
        assertNull(BaselineInput(qty = "5").toBaseline())
        assertNull(BaselineInput(unit = HarvestUnits.KG).toBaseline())
        assertNull(BaselineInput(qty = "0", unit = HarvestUnits.KG).toBaseline())
    }

    @Test
    fun `the baseline lands on the farm row with its canonical kilograms`() = runBlocking {
        val farmDao = FakeFarmDao()
        val farmId = repository(farmDao = farmDao).createLocal(
            name = "Sigona",
            crops = CropSelection(growing = setOf("beans")),
            baseline = BaselineInput(qty = "6", unit = HarvestUnits.BAGS, bagSizeCenti = 5_000)
                .toBaseline(fallbackCrop = "beans"),
        )

        val row = farmDao.rows.getValue(farmId)
        assertEquals(600L, row.baselineYieldCenti)
        assertEquals(HarvestUnits.BAGS, row.baselineYieldUnit)
        assertEquals(30_000L, row.baselineYieldKgCenti)
        assertEquals("beans", row.baselineCrop)
    }

    // ── the ward is stamped, never typed ───────────────────────────────────

    @Test
    fun `the ward on a registration is the registering agent's own`() = runBlocking {
        val farmDao = FakeFarmDao()
        val farmId = repository(farmDao = farmDao).createLocalForFarmer(
            farmerName = "Sila Serem",
            farmerPhone = null,
            shambaName = "Sigona",
            registeredByAgentId = "agent-1",
            ward = "Chepterwai",
        )

        assertEquals("Chepterwai", farmDao.rows.getValue(farmId).ward)
        // Still no referrer. This is a registration, not a sale, and the
        // commission engine is live and backdated to 1 June.
        assertNull(farmDao.rows.getValue(farmId).referrerAgentId)
    }

    // ── the pull must not eat any of it ────────────────────────────────────

    @Test
    fun `a pull preserves every column the server does not yet carry`() = runBlocking {
        val farmDao = FakeFarmDao()
        farmDao.rows["farm-1"] = FarmEntity(
            id = "farm-1",
            name = "Sigona",
            cropType = "beans",
            acres = 3.0,
            locationLat = 0.1874,
            locationLng = 35.1021,
            locationAccuracyM = 8f,
            locationSource = LocationSource.GPS,
            locationCapturedAt = "2026-08-13T10:43:00Z",
            locationConfirmedAt = "2026-08-13T10:44:00Z",
            ward = "Chepterwai",
            baselineYieldCenti = 600,
            baselineYieldUnit = HarvestUnits.BAGS,
            baselineYieldKgCenti = 30_000,
            baselineCrop = "beans",
            waterSource = null,
            createdAt = "2026-08-13T09:00:00Z",
            updatedAt = "2026-08-13T09:00:00Z",
            deletedAt = null,
            pendingSync = false,
            syncAction = SyncAction.UPDATE,
        )
        val api = object : FakeKumeaApi() {
            override suspend fun getFarms(
                since: String?,
                includeDeleted: Boolean,
                registeredBy: String?,
            ): List<FarmResponse> = listOf(
                // A server that has not been patched yet: it knows nothing about
                // accuracy, ward or baseline, so it sends none of them.
                FarmResponse(
                    id = "farm-1",
                    name = "Sigona",
                    createdAt = "2026-08-13T09:00:00Z",
                    updatedAt = "2026-08-13T11:00:00Z",
                )
            )
        }

        repository(farmDao = farmDao, api = api).pullSince()

        val row = farmDao.rows.getValue("farm-1")
        assertEquals(8f, row.locationAccuracyM)
        assertEquals(LocationSource.GPS, row.locationSource)
        assertEquals("2026-08-13T10:43:00Z", row.locationCapturedAt)
        assertEquals("2026-08-13T10:44:00Z", row.locationConfirmedAt)
        assertEquals("Chepterwai", row.ward)
        // The one that cannot be recovered if it is lost: nobody can be asked
        // in December what they harvested the season before last.
        assertEquals(30_000L, row.baselineYieldKgCenti)
        assertEquals("beans", row.baselineCrop)
    }

    @Test
    fun `a pull cannot drop interested rows from a farm it did not touch`() = runBlocking {
        val farmDao = FakeFarmDao()
        val cropDao = FakeFarmCropDao()
        val farmId = repository(farmDao = farmDao, cropDao = cropDao).createLocal(
            name = "Sigona",
            crops = CropSelection(growing = setOf("beans"), interested = setOf("soybean")),
        )

        // The server already has this farm from an earlier push and knows
        // nothing about crops — they are device-only until the KWAP-03 server
        // patch. The row is still pendingSync, so the pull must leave it
        // entirely alone: row and crop set together.
        val api = object : FakeKumeaApi() {
            override suspend fun getFarms(
                since: String?,
                includeDeleted: Boolean,
                registeredBy: String?,
            ): List<FarmResponse> = listOf(
                FarmResponse(
                    id = farmId,
                    name = "Sigona",
                    createdAt = "2026-08-13T09:00:00Z",
                    updatedAt = "2026-08-13T11:00:00Z",
                )
            )
        }
        repository(farmDao = farmDao, cropDao = cropDao, api = api).pullSince()

        val statuses = cropDao.getByFarmOnce(farmId).associate { it.crop to it.status }
        assertTrue("the interest signal must survive a sync", statuses["soybean"] == CropStatus.INTERESTED)
        assertEquals(2, statuses.size)
    }
}
