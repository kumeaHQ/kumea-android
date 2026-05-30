package co.ke.kumea.data.repository

import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.FieldEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.FieldCreateRequest
import co.ke.kumea.data.remote.dto.FieldUpdateRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock

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
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) {
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
     * Called by the sync trigger (manual refresh today; FieldSyncWorker later).
     */
    suspend fun pushPending() {
        val pending = fieldDao.getPendingSync()
        for (field in pending) {
            try {
                when (field.syncAction) {
                    SyncAction.CREATE -> {
                        val response = api.createField(FieldCreateRequest(
                            id = field.id,
                            farmId = field.farmId,
                            name = field.name,
                            acres = field.acres,
                            cropType = field.cropType,
                        ))
                        if (response.isSuccessful) {
                            val serverField = response.body()!!
                            fieldDao.markSynced(field.id, serverField.updatedAt)
                        } else if (response.code() == 409) {
                            // Conflict — server wins, log and discard local
                            val serverBody = response.errorBody()?.string() ?: "{}"
                            recordConflict(field, serverBody, "create_409")
                            fieldDao.upsert(field.copy(pendingSync = false))
                        }
                    }
                    SyncAction.UPDATE -> {
                        val response = api.updateField(field.id, FieldUpdateRequest(
                            name = field.name,
                            acres = field.acres,
                            cropType = field.cropType,
                            updatedAt = field.updatedAt,
                        ))
                        if (response.isSuccessful) {
                            val serverField = response.body()!!
                            fieldDao.markSynced(field.id, serverField.updatedAt)
                        } else if (response.code() == 409) {
                            val serverBody = response.errorBody()?.string() ?: "{}"
                            recordConflict(field, serverBody, "update_409")
                            fieldDao.upsert(field.copy(pendingSync = false))
                        }
                    }
                    SyncAction.DELETE -> {
                        val response = api.deleteField(field.id)
                        if (response.isSuccessful) {
                            // DELETE returns 204 — no body, just mark synced
                            val now = Clock.System.now().toString()
                            fieldDao.markSyncedDelete(field.id, field.deletedAt ?: now)
                        }
                        // DELETE never returns 409 per Ticket 1.3; if we somehow get one,
                        // leave it pending and let the next pull reconcile.
                    }
                }
            } catch (e: Exception) {
                // Network error — re-throw so WorkManager (or the refresh caller)
                // can retry with exponential backoff.
                throw e
            }
        }
    }

    /**
     * Pull server changes since the latest local updatedAt.
     *
     * Must run AFTER the farm pull in a sync cycle: a field's CASCADE foreign key
     * requires its parent farm row to exist locally first. The refresh path pulls
     * farms then fields for exactly this reason.
     */
    suspend fun pullSince() {
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

        if (serverFields.isEmpty()) return

        val localEntities = serverFields.map { server ->
            FieldEntity(
                id = server.id,
                farmId = server.farmId,
                name = server.name,
                acres = server.acres,
                cropType = server.cropType,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // Invariant (same as FarmRepository): never let pull clobber a row that
        // push hasn't reconciled yet. pushPending() runs first; this is the
        // defensive double-check for rows that became pending in between.
        val pendingIds = fieldDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            fieldDao.upsertAll(cleanEntities)
        }
    }

    private suspend fun recordConflict(local: FieldEntity, serverPayload: String, conflictType: String) {
        val entity = SyncConflictEntity(
            id = UUID.randomUUID().toString(),
            entityType = "field",
            entityId = local.id,
            localPayload = local.toString(),
            serverPayload = serverPayload,
            conflictType = conflictType,
            occurredAt = Clock.System.now().toString(),
        )
        syncConflictDao.insert(entity)
    }
}
