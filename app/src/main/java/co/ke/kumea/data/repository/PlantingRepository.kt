package co.ke.kumea.data.repository

import androidx.room.withTransaction
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.KumeaDatabase
import co.ke.kumea.data.local.NoteSource
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.local.PlantingDao
import co.ke.kumea.data.local.PlantingEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.TrialRole
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.PlantingCreateRequest
import co.ke.kumea.data.remote.dto.PlantingUpdateRequest
import co.ke.kumea.data.sync.PushDisposition
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncRejectionRecorder
import co.ke.kumea.data.sync.SyncableRepository
import co.ke.kumea.util.Area
import co.ke.kumea.util.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first Planting sync (KWAP-03-V2 §2.3), same shape as
 * [HarvestRepository] — parent is Farm rather than Field.
 *
 * ⚠️ NOT BOUND INTO `Set<SyncableRepository>`. There is no `/plantings` route on
 * the server (checked against `kumea-api c83917f`), so every push would 404. The
 * push/pull code is written and reviewable; the single `@Binds @IntoSet` that
 * arms it is commented out in `di/RepositoryModule.kt`. Uncomment it in the
 * commit that verifies the deployed contract.
 *
 * The stakes of getting that wrong have dropped, and deliberately so. Under the
 * old `{403}` terminal set, binding early meant every planting re-sent for ever
 * at the head of the queue — unrecoverable without a code change. [RetryPolicy]
 * now bounds a 404 at three attempts and surfaces the payload, so a premature
 * binding costs three requests and a visible rejection instead. That is the
 * whole point of fixing the classifier first: the server half can now land
 * incrementally rather than having to be atomic with the client.
 *
 * ── THE LINKED PURCHASE (§2.5) ──────────────────────────────────────────────
 *
 * Seed cost is asked once, in the planting flow, and writing it also writes a
 * PURCHASE note so it appears in the farmer's ledger. The two are created in ONE
 * Room transaction and the note carries `sourceType`/`sourceId` back to the
 * planting, which is what makes the single-capture rule safe rather than merely
 * convenient: the ledger renders that row read-only, so a farmer cannot look at
 * a ledger that already lists their seed cost, not recognise it, and add it
 * again. "Invested" doubling is the failure this prevents.
 */
@Singleton
class PlantingRepository @Inject constructor(
    private val db: KumeaDatabase,
    private val plantingDao: PlantingDao,
    private val farmDao: FarmDao,
    private val noteRepository: NoteRepository,
    private val rejections: SyncRejectionRecorder,
    private val api: KumeaApi,
) : SyncableRepository {

    fun getActiveByFarm(farmId: String): Flow<List<PlantingEntity>> =
        plantingDao.getActiveByFarm(farmId)

    suspend fun getById(id: String): PlantingEntity? = plantingDao.getById(id)

    /** The planted area the yield sanity line divides by (§2.8). Null if never asked. */
    suspend fun getLatestForFarm(farmId: String): PlantingEntity? =
        plantingDao.getLatestForFarm(farmId)

    /**
     * One planting, plus its linked seed Purchase when a cost was given.
     *
     * @param fieldId the farm's single auto-created Field — needed ONLY because
     *   `NoteEntity.fieldId` is still the note's parent (§2.2 leaves the Field
     *   schema untouched). The planting itself is farm-level.
     */
    suspend fun createLocal(
        farmId: String,
        fieldId: String,
        plantedOn: String,
        crop: String,
        seedVariety: String?,
        seedKgCenti: Long,
        plantedAreaCenti: Long,
        seedCostCents: Long?,
        trialRole: String = TrialRole.NONE,
    ): String {
        require(crop.isNotBlank()) { "a planting must say what was planted — see PlantingEntity" }
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        // ONE transaction: a planting whose seed cost silently failed to become a
        // Purchase is worse than neither, because the farmer was told it was
        // recorded and the ledger disagrees.
        db.withTransaction {
            plantingDao.upsert(
                PlantingEntity(
                    id = id,
                    farmId = farmId,
                    plantedOn = plantedOn,
                    crop = crop,
                    seedVariety = seedVariety?.takeIf { it.isNotBlank() },
                    seedKgCenti = seedKgCenti,
                    plantedAreaCenti = plantedAreaCenti,
                    seedCostCents = seedCostCents,
                    trialRole = trialRole,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    pendingSync = true,
                    syncAction = SyncAction.CREATE,
                )
            )
            if (seedCostCents != null) {
                noteRepository.createLocal(
                    fieldId = fieldId,
                    type = NoteType.PURCHASE,
                    body = seedPurchaseBody(crop),
                    amountCents = seedCostCents,
                    occurredAt = plantedOn,
                    costCategory = CostCategory.SEED,
                    sourceType = NoteSource.PLANTING,
                    sourceId = id,
                )
            }
        }
        return id
    }

    /**
     * Edit a planting, keeping its linked Purchase in step: a changed cost
     * UPDATES the existing note, a cleared cost soft-deletes it, and a cost added
     * to a planting that had none creates one. Exactly one seed Purchase per
     * planting, in every path.
     */
    suspend fun updateLocal(
        id: String,
        fieldId: String,
        plantedOn: String,
        crop: String,
        seedVariety: String?,
        seedKgCenti: Long,
        plantedAreaCenti: Long,
        seedCostCents: Long?,
        trialRole: String,
    ) {
        require(crop.isNotBlank()) { "a planting must say what was planted" }
        val existing = plantingDao.getById(id) ?: return
        val now = Clock.System.now().toString()
        db.withTransaction {
            plantingDao.upsert(
                existing.copy(
                    plantedOn = plantedOn,
                    crop = crop,
                    seedVariety = seedVariety?.takeIf { it.isNotBlank() },
                    seedKgCenti = seedKgCenti,
                    plantedAreaCenti = plantedAreaCenti,
                    seedCostCents = seedCostCents,
                    trialRole = trialRole,
                    updatedAt = now,
                    pendingSync = true,
                    // Never flip an unsynced CREATE to UPDATE — see
                    // HarvestRepository.updateLocal for the 404 that causes.
                    syncAction = if (existing.syncAction == SyncAction.CREATE && existing.pendingSync) {
                        SyncAction.CREATE
                    } else {
                        SyncAction.UPDATE
                    },
                )
            )
            val linked = noteRepository.findBySource(NoteSource.PLANTING, id)
            when {
                seedCostCents != null && linked != null -> noteRepository.updateLocal(
                    id = linked.id,
                    body = seedPurchaseBody(crop),
                    amountCents = seedCostCents,
                    occurredAt = plantedOn,
                )
                seedCostCents != null -> noteRepository.createLocal(
                    fieldId = fieldId,
                    type = NoteType.PURCHASE,
                    body = seedPurchaseBody(crop),
                    amountCents = seedCostCents,
                    occurredAt = plantedOn,
                    costCategory = CostCategory.SEED,
                    sourceType = NoteSource.PLANTING,
                    sourceId = id,
                )
                linked != null -> noteRepository.deleteLocal(linked.id)
            }
        }
    }

    /** Soft-delete, taking the linked seed Purchase with it (§2.5). */
    suspend fun deleteLocal(id: String) {
        val planting = plantingDao.getById(id) ?: return
        val now = Clock.System.now().toString()
        db.withTransaction {
            plantingDao.upsert(
                planting.copy(
                    deletedAt = now,
                    updatedAt = now,
                    pendingSync = true,
                    syncAction = SyncAction.DELETE,
                )
            )
            // Leaving it behind would strand a seed cost in the ledger with a
            // read-only badge pointing at a planting that no longer exists.
            noteRepository.findBySource(NoteSource.PLANTING, id)?.let {
                noteRepository.deleteLocal(it.id)
            }
        }
    }

    private fun seedPurchaseBody(crop: String): String = "Seed — $crop"

    override suspend fun pushPending(): PushReport {
        val pending = plantingDao.getPendingSync()
        val report = PushReportBuilder("Plantings")
        report.found = pending.size

        // FK guard, same as Field→Farm: a planting whose farm the server has not
        // seen yet is deferred, not failed, and retried next cycle.
        val unsyncedFarmIds = farmDao.getPendingSync()
            .filter { it.syncAction == SyncAction.CREATE }
            .map { it.id }
            .toSet()

        for (planting in pending) {
            if (planting.farmId in unsyncedFarmIds) {
                report.deferred("farm_not_on_server")
                continue
            }
            when (planting.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createPlanting(
                        PlantingCreateRequest(
                            id = planting.id,
                            farmId = planting.farmId,
                            plantedOn = planting.plantedOn,
                            crop = planting.crop,
                            seedVariety = planting.seedVariety,
                            seedKg = Quantity.formatCenti(planting.seedKgCenti),
                            plantedArea = Area.formatCenti(planting.plantedAreaCenti),
                            seedCostCents = planting.seedCostCents?.toString(),
                            trialRole = planting.trialRole,
                        )
                    )
                    if (response.isSuccessful) {
                        plantingDao.markSynced(planting.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(planting, response.code(), response.errorBody()?.string(), "create")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.UPDATE -> {
                    val response = api.updatePlanting(
                        planting.id,
                        PlantingUpdateRequest(
                            plantedOn = planting.plantedOn,
                            crop = planting.crop,
                            seedVariety = planting.seedVariety,
                            seedKg = Quantity.formatCenti(planting.seedKgCenti),
                            plantedArea = Area.formatCenti(planting.plantedAreaCenti),
                            seedCostCents = planting.seedCostCents?.toString(),
                            trialRole = planting.trialRole,
                            updatedAt = planting.updatedAt,
                        )
                    )
                    if (response.isSuccessful) {
                        plantingDao.markSynced(planting.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(planting, response.code(), response.errorBody()?.string(), "update")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deletePlanting(planting.id)
                    if (response.isSuccessful) {
                        plantingDao.markSyncedDelete(planting.id, planting.deletedAt ?: Clock.System.now().toString())
                        report.succeeded()
                    } else {
                        applyFailure(planting, response.code(), response.errorBody()?.string(), "delete")
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    /**
     * Single exit for every non-2xx (see [RetryPolicy]).
     *
     * This matters more here than anywhere else: `plantings` has NO server route
     * yet, so every push would 404. Under the old `{403}` terminal set that was
     * an infinite retry, which is precisely why this repository is unbound. With
     * the bounded 404 budget a premature binding degrades to three attempts and
     * a surfaced rejection with the payload intact — recoverable, not fatal.
     */
    private suspend fun applyFailure(
        planting: PlantingEntity,
        code: Int,
        serverBody: String?,
        verb: String,
    ) {
        val disposition = rejections.onFailure(
            entityType = "planting",
            entityId = planting.id,
            localPayload = planting.toString(),
            code = code,
            serverPayload = serverBody ?: "{}",
            verb = verb,
        )
        if (disposition != PushDisposition.RETRY) {
            plantingDao.upsert(planting.copy(pendingSync = false))
        }
    }

    override suspend fun pullSince(): Int {
        val since = plantingDao.getLatestUpdatedAt()
        val serverPlantings = api.getPlantings(since = since, includeDeleted = true)
        if (serverPlantings.isEmpty()) return 0

        val localEntities = serverPlantings.mapNotNull { server ->
            // An unparseable quantity is a contract violation — skip the row
            // rather than storing a corrupted area the impact report divides by.
            val seedKgCenti = Quantity.parseToCenti(server.seedKg) ?: return@mapNotNull null
            val plantedAreaCenti = Area.parseToCenti(server.plantedArea) ?: return@mapNotNull null
            PlantingEntity(
                id = server.id,
                farmId = server.farmId,
                plantedOn = server.plantedOn,
                crop = server.crop,
                seedVariety = server.seedVariety,
                seedKgCenti = seedKgCenti,
                plantedAreaCenti = plantedAreaCenti,
                seedCostCents = server.seedCostCents?.toLongOrNull(),
                trialRole = server.trialRole,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        val localFarmIds = farmDao.getAllIds().toSet()
        val owned = localEntities.filter { it.farmId in localFarmIds }
        val pendingIds = plantingDao.getPendingSync().map { it.id }.toSet()
        val clean = owned.filter { it.id !in pendingIds }
        if (clean.isNotEmpty()) {
            plantingDao.upsertAll(clean)
        }
        return clean.size
    }

}
