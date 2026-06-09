package co.ke.kumea.data.repository

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import co.ke.kumea.data.sync.SyncableRepository

/**
 * Orchestrates local Room persistence with remote API for offline-first Farm sync.
 */
@Singleton
class FarmRepository @Inject constructor(
    private val farmDao: FarmDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {
    /**
     * Observe all active farms (live, via Room Flow).
     */
    fun getAllActive(): Flow<List<FarmEntity>> = farmDao.getAllActive()

    /**
     * Create a farm locally (offline-first). Returns the generated UUID.
     */
    suspend fun createLocal(
        name: String,
        locationLat: Double?,
        locationLng: Double?,
        waterSource: String?,
        // T4: the Agent who registered this farmer (optional, non-commercial).
        // Default null keeps existing callers source-compatible.
        referrerAgentId: String? = null,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val farm = FarmEntity(
            id = id,
            name = name,
            locationLat = locationLat,
            locationLng = locationLng,
            waterSource = waterSource,
            referrerAgentId = referrerAgentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            pendingSync = true,
            syncAction = SyncAction.CREATE,
        )
        farmDao.upsert(farm)
        return id
    }

    /**
     * Update a farm locally (offline-first).
     */
    suspend fun updateLocal(id: String, name: String?, locationLat: Double?, locationLng: Double?, waterSource: String?) {
        val now = Clock.System.now().toString()
        // Read current state to merge with partial update
        // In production we'd use a proper partial-update pattern;
        // for Sprint 0 this is sufficient.
        var farm = farmDao.getPendingSync().find { it.id == id }
            ?: return  // farm not found; caller should handle
        farm = farm.copy(
            name = name ?: farm.name,
            locationLat = locationLat ?: farm.locationLat,
            locationLng = locationLng ?: farm.locationLng,
            waterSource = waterSource ?: farm.waterSource,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.UPDATE,
        )
        farmDao.upsert(farm)
    }

    /**
     * Soft-delete a farm locally (offline-first).
     */
    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        var farm = farmDao.getPendingSync().find { it.id == id }
            ?: return
        farm = farm.copy(
            deletedAt = now,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.DELETE,
        )
        farmDao.upsert(farm)
    }

    /**
     * Push all pending local changes to the server.
     * Called by SyncWorker.
     */
    override suspend fun pushPending(): Int {
        val pending = farmDao.getPendingSync()
        var pushed = 0
        for (farm in pending) {
            try {
                when (farm.syncAction) {
                    SyncAction.CREATE -> {
                        val response = api.createFarm(FarmCreateRequest(
                            id = farm.id,
                            name = farm.name,
                            locationLat = farm.locationLat,
                            locationLng = farm.locationLng,
                            waterSource = farm.waterSource,
                            referrerAgentId = farm.referrerAgentId,
                        ))
                        if (response.isSuccessful) {
                            val serverFarm = response.body()!!
                            farmDao.markSynced(farm.id, serverFarm.updatedAt)
                            pushed++
                        } else if (response.code() == 409) {
                            // Conflict — server wins, log and discard local
                            val serverBody = response.errorBody()?.string() ?: "{}"
                            recordConflict(farm, serverBody, "create_409")
                            // If we got the server version in the response body, replace local
                            // Otherwise we could fetch; for Sprint 0 we just clear pendingSync
                            farmDao.upsert(farm.copy(pendingSync = false))
                        }
                    }
                    SyncAction.UPDATE -> {
                        val response = api.updateFarm(farm.id, FarmUpdateRequest(
                            name = farm.name,
                            locationLat = farm.locationLat,
                            locationLng = farm.locationLng,
                            waterSource = farm.waterSource,
                            updatedAt = farm.updatedAt,
                        ))
                        if (response.isSuccessful) {
                            val serverFarm = response.body()!!
                            farmDao.markSynced(farm.id, serverFarm.updatedAt)
                            pushed++
                        } else if (response.code() == 409) {
                            val serverBody = response.errorBody()?.string() ?: "{}"
                            recordConflict(farm, serverBody, "update_409")
                            farmDao.upsert(farm.copy(pendingSync = false))
                        }
                    }
                    SyncAction.DELETE -> {
                        val response = api.deleteFarm(farm.id)
                        if (response.isSuccessful) {
                            // DELETE returns 204 — no body, just mark synced
                            val now = Clock.System.now().toString()
                            farmDao.markSyncedDelete(farm.id, farm.deletedAt ?: now)
                            pushed++
                        }
                        // DELETE never returns 409 per Ticket 1.3; if we somehow get one,
                        // just clear pendingSync and let the row reconcile on next pull
                    }
                }
            } catch (e: Exception) {
                // Network error — WorkManager will retry with exponential backoff
                // Re-throw so WorkManager marks the worker as retryable
                throw e
            }
        }
        return pushed
    }

    /**
     * Pull server changes since the latest local updatedAt.
     * Called by SyncWorker.
     */
    override suspend fun pullSince(): Int {
        val since = farmDao.getLatestUpdatedAt()
        val serverFarms = try {
            api.getFarms(since = since, includeDeleted = true)
        } catch (e: Exception) {
            // Network error — WorkManager will retry
            throw e
        }

        if (serverFarms.isEmpty()) return 0

        val localEntities = serverFarms.map { server ->
            FarmEntity(
                id = server.id,
                name = server.name,
                locationLat = server.locationLat,
                locationLng = server.locationLng,
                waterSource = server.waterSource,
                referrerAgentId = server.referrerAgentId,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // Defensive: skip rows that still have pending local edits.
        // pushPending() runs first in the sync worker, so by this point
        // all pending rows should already be reconciled. This double-check
        // protects against the edge case where a row became pending between
        // the push and pull steps.
        //
        // Invariant: we never let pull overwrite a row that push hasn't
        // reconciled yet. If we skipped this filter, the pull could clobber
        // a pending local edit with stale server data.
        val pendingIds = farmDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            farmDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
    }

    private suspend fun recordConflict(local: FarmEntity, serverPayload: String, conflictType: String) {
        val entity = SyncConflictEntity(
            id = UUID.randomUUID().toString(),
            entityType = "farm",
            entityId = local.id,
            localPayload = local.toString(),
            serverPayload = serverPayload,
            conflictType = conflictType,
            occurredAt = Clock.System.now().toString(),
        )
        syncConflictDao.insert(entity)
    }
}
