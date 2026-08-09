package co.ke.kumea.data.repository

import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.parseErrorCode
import co.ke.kumea.data.remote.dto.FarmCreateRequest
import co.ke.kumea.data.remote.dto.FarmUpdateRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncableRepository

@Singleton
class FarmRepository @Inject constructor(
    private val farmDao: FarmDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {

    fun getAllActive(): Flow<List<FarmEntity>> = farmDao.getAllActive()

    /**
     * Single farm lookup, pending or synced. The previous implementation searched
     * getPendingSync() only, so a farm that had synced returned null — which made
     * the note-screen's Main-field inheritance silently fall back to defaults.
     */
    suspend fun getById(id: String): FarmEntity? = farmDao.getById(id)

    suspend fun createLocal(
        name: String,
        cropType: String? = null,
        acres: Double? = null,
        locationLat: Double? = null,
        locationLng: Double? = null,
        useGps: Boolean = false,
        waterSource: String? = null,
        referrerAgentId: String? = null,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val farm = FarmEntity(
            id = id,
            name = name,
            cropType = cropType,
            acres = acres,
            locationLat = locationLat,
            locationLng = locationLng,
            useGps = useGps,
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

    suspend fun updateLocal(id: String, name: String?, locationLat: Double?, locationLng: Double?, waterSource: String?) {
        val now = Clock.System.now().toString()
        var farm = farmDao.getPendingSync().find { it.id == id } ?: return
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

    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        var farm = farmDao.getPendingSync().find { it.id == id } ?: return
        farm = farm.copy(
            deletedAt = now,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.DELETE,
        )
        farmDao.upsert(farm)
    }

    override suspend fun pushPending(): PushReport {
        val pending = farmDao.getPendingSync()
        val report = PushReportBuilder("Farms")
        report.found = pending.size
        for (farm in pending) {
            when (farm.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createFarm(FarmCreateRequest(
                        id = farm.id,
                        name = farm.name,
                        cropType = farm.cropType,
                        acres = farm.acres,
                        locationLat = farm.locationLat,
                        locationLng = farm.locationLng,
                        useGps = farm.useGps,
                        waterSource = farm.waterSource,
                        referrerAgentId = farm.referrerAgentId,
                    ))
                    if (response.isSuccessful) {
                        farmDao.markSynced(farm.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        val serverBody = response.errorBody()?.string()
                        when {
                            response.code() == 400 && parseErrorCode(serverBody) == "referrer_agent_not_found" -> {
                                report.deferred("referrer agent not synced yet")
                            }
                            response.code() == 409 -> {
                                recordConflict(farm, serverBody ?: "{}", "create_409")
                                farmDao.upsert(farm.copy(pendingSync = false))
                                report.failed("409")
                            }
                            else -> report.failed(response.code().toString())
                        }
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
                        farmDao.markSynced(farm.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else if (response.code() == 409) {
                        recordConflict(farm, response.errorBody()?.string() ?: "{}", "update_409")
                        farmDao.upsert(farm.copy(pendingSync = false))
                        report.failed("409")
                    } else {
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deleteFarm(farm.id)
                    if (response.isSuccessful) {
                        val now = Clock.System.now().toString()
                        farmDao.markSyncedDelete(farm.id, farm.deletedAt ?: now)
                        report.succeeded()
                    } else {
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    override suspend fun pullSince(): Int {
        val since = farmDao.getLatestUpdatedAt()
        val serverFarms = try {
            api.getFarms(since = since, includeDeleted = true)
        } catch (e: Exception) {
            throw e
        }
        if (serverFarms.isEmpty()) return 0
        val localEntities = serverFarms.map { server ->
            FarmEntity(
                id = server.id,
                name = server.name,
                cropType = server.cropType,
                acres = server.acres,
                locationLat = server.locationLat,
                locationLng = server.locationLng,
                useGps = server.useGps,
                waterSource = server.waterSource,
                referrerAgentId = server.referrerAgentId,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }
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
