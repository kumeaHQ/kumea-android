package co.ke.kumea.data.repository

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.FieldEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.FieldCreateRequest
import co.ke.kumea.data.remote.dto.FieldUpdateRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import co.ke.kumea.data.sync.PushDisposition
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncRejectionRecorder
import co.ke.kumea.data.sync.SyncableRepository

/**
 * Offline-first Field sync — a direct copy of FarmRepository (Ticket 3.1).
 *
 * The only substantive differences from Farm are the four expected ones:
 *   - createLocal takes a farmId (Field belongs to Farm)
 *   - the API surface is /fields instead of /farms
 *   - acres is a String, never Double (kept as-is end-to-end, no parsing)
 *   - Field/Fields naming throughout
 * Everything else is a mechanical rename.
 */
@Singleton
class FieldRepository @Inject constructor(
    private val fieldDao: FieldDao,
    private val farmDao: FarmDao,
    private val rejections: SyncRejectionRecorder,
    private val api: KumeaApi,
) : SyncableRepository {
    /** Observe all active fields (live, via Room Flow). */
    fun getAllActive(): Flow<List<FieldEntity>> = fieldDao.getAllActive()

    /** Observe active fields for a single farm. */
    fun getActiveByFarm(farmId: String): Flow<List<FieldEntity>> = fieldDao.getActiveByFarm(farmId)

    /**
     * Create a field locally (offline-first). acres is a decimal string the
     * caller already validated/normalised — it is stored verbatim, never parsed
     * to a number. Returns the generated UUID.
     */
    suspend fun createLocal(farmId: String, name: String, acres: String, cropType: String?): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val field = FieldEntity(
            id = id,
            farmId = farmId,
            name = name,
            acres = acres,
            cropType = cropType,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            pendingSync = true,
            syncAction = SyncAction.CREATE,
        )
        fieldDao.upsert(field)
        return id
    }

    /**
     * Update a field locally (offline-first).
     *
     * NOTE: mirrors FarmRepository exactly, including reading current state via
     * getPendingSync().find — which only sees rows already pending. Editing a
     * fully-synced row through this path is a latent gap inherited from the Farm
     * pattern (no UI exercises it yet for either entity). Flagged in the 3.1
     * generalisation report rather than silently "fixed" here, so Farm and Field
     * stay a true 1:1 copy.
     */
    suspend fun updateLocal(id: String, name: String?, acres: String?, cropType: String?) {
        val now = Clock.System.now().toString()
        var field = fieldDao.getPendingSync().find { it.id == id }
            ?: return  // field not found among pending; caller should handle
        field = field.copy(
            name = name ?: field.name,
            acres = acres ?: field.acres,
            cropType = cropType ?: field.cropType,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.UPDATE,
        )
        fieldDao.upsert(field)
    }

    /**
     * Set the planting date (Build-2, offline-first). Unlike updateLocal, this
     * DELIBERATELY works on synced rows too (fieldDao.getById, not the
     * pendingSync-only lookup) — a farmer records planting on a field that
     * synced weeks ago. If the row is still an unpushed CREATE, it stays a
     * CREATE (the date rides along in the create body); otherwise it becomes a
     * pending UPDATE.
     */
    suspend fun setPlantedAt(id: String, plantedAt: String) {
        val now = Clock.System.now().toString()
        val field = fieldDao.getById(id) ?: return
        val action = if (field.pendingSync && field.syncAction == SyncAction.CREATE) {
            SyncAction.CREATE
        } else {
            SyncAction.UPDATE
        }
        fieldDao.upsert(
            field.copy(plantedAt = plantedAt, updatedAt = now, pendingSync = true, syncAction = action)
        )
    }

    /** Soft-delete a field locally (offline-first). */
    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        var field = fieldDao.getPendingSync().find { it.id == id }
            ?: return
        field = field.copy(
            deletedAt = now,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.DELETE,
        )
        fieldDao.upsert(field)
    }

    /**
     * Push all pending local changes to the server.
     * Called by the sync trigger (manual refresh today; SyncWorker later).
     */
    override suspend fun pushPending(): PushReport {
        val pending = fieldDao.getPendingSync()
        val report = PushReportBuilder("Fields")
        report.found = pending.size
        for (field in pending) {
            when (field.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createField(FieldCreateRequest(
                        id = field.id,
                        farmId = field.farmId,
                        name = field.name,
                        acres = field.acres,
                        cropType = field.cropType,
                        plantedAt = field.plantedAt,
                    ))
                    if (response.isSuccessful) {
                        fieldDao.markSynced(field.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(field, response.code(), response.errorBody()?.string(), "create")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.UPDATE -> {
                    val response = api.updateField(field.id, FieldUpdateRequest(
                        name = field.name,
                        acres = field.acres,
                        cropType = field.cropType,
                        plantedAt = field.plantedAt,
                        updatedAt = field.updatedAt,
                    ))
                    if (response.isSuccessful) {
                        fieldDao.markSynced(field.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(field, response.code(), response.errorBody()?.string(), "update")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deleteField(field.id)
                    if (response.isSuccessful) {
                        // DELETE returns 204 — no body, just mark synced.
                        val now = Clock.System.now().toString()
                        fieldDao.markSyncedDelete(field.id, field.deletedAt ?: now)
                        report.succeeded()
                    } else {
                        applyFailure(field, response.code(), response.errorBody()?.string(), "delete")
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
     * Must run AFTER the farm pull in a sync cycle: a field's CASCADE foreign key
     * requires its parent farm row to exist locally first. The refresh path pulls
     * farms then fields for exactly this reason.
     */
    override suspend fun pullSince(): Int {
        val since = fieldDao.getLatestUpdatedAt()
        // includeDeleted = true so soft-deleted rows (deletedAt set) come down in
        // the delta and offline devices can reconcile a remote delete (AC 17).
        // DELIBERATE DEVIATION FROM FARM: FarmRepository.pullSince() omits this
        // (defaults to false), so Farm currently never propagates remote deletes —
        // a latent gap the Field copy surfaced. Worth back-porting to Farm.
        val serverFields = try {
            api.getFields(since = since, includeDeleted = true)
        } catch (e: Exception) {
            throw e
        }

        if (serverFields.isEmpty()) return 0

        val localEntities = serverFields.map { server ->
            FieldEntity(
                id = server.id,
                farmId = server.farmId,
                name = server.name,
                acres = server.acres,
                cropType = server.cropType,
                plantedAt = server.plantedAt,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // Guard: only upsert fields whose parent farm exists locally.
        // The API returns all user fields across all farms — a farm the device
        // hasn't pulled yet (or lost during a failed sync cycle) will fail the
        // Room FK constraint. Skip those; the farm pull runs before field pull.
        val localFarmIds = farmDao.getAllIds().toSet()
        val orphanedEntities = localEntities.filter { it.farmId !in localFarmIds }
        val ownedEntities = localEntities.filter { it.farmId in localFarmIds }

        // Invariant (same as FarmRepository): never let pull clobber a row that
        // push hasn't reconciled yet. pushPending() runs first; this is the
        // defensive double-check for rows that became pending in between.
        val pendingIds = fieldDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = ownedEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            fieldDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
    }

    /** Single exit for every non-2xx — classification lives in [RetryPolicy]. */
    private suspend fun applyFailure(field: FieldEntity, code: Int, serverBody: String?, verb: String) {
        val disposition = rejections.onFailure(
            entityType = "field",
            entityId = field.id,
            localPayload = field.toString(),
            code = code,
            serverPayload = serverBody ?: "{}",
            verb = verb,
        )
        if (disposition != PushDisposition.RETRY) {
            fieldDao.upsert(field.copy(pendingSync = false))
        }
    }
}
