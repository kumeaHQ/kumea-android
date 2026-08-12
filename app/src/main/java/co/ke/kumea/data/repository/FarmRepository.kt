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
import co.ke.kumea.data.remote.dto.FarmResponse
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

    /** The register: farms [agentId] typed in (KWAP-01 step 4). Live, offline-readable. */
    fun getRegisteredBy(agentId: String): Flow<List<FarmEntity>> = farmDao.getRegisteredBy(agentId)

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

    /**
     * Register a farmer on someone else's behalf (KWAP-01 step 4) — the officer
     * and village-agent path, as opposed to [createLocal]'s self-registration.
     *
     * Three deliberate choices, each of which has a way of going wrong:
     *
     * ① `referrerAgentId` is not a parameter. It cannot be passed, so it cannot
     *    be passed by mistake. Registering a farmer is not a sale: the
     *    commission engine is live and accrues effective 1 June, so a referrer
     *    on ~395 free-product research farmers would be real money owed to
     *    agents who did nothing — wrong in the ledger, not merely on screen
     *    (KWAP-01 §6). Referral is a separate, explicit act when a commercial
     *    relationship actually starts.
     *
     * ② `registeredByAgentId` is set locally to the caller's own agent id, even
     *    though the server derives the same value and ignores whatever we send.
     *    That is not redundancy — the row has to appear in the officer's own
     *    register the instant she saves it, offline, and the directory filters
     *    on this column. The server remains the authority; a pull overwrites it
     *    with the derived value, which agrees.
     *
     * ③ `farmerUserId` stays null. KWAP-STEP2-DECISIONS §2 deferred creating
     *    Users for KWAP farmers, so the server keeps the officer as owner and
     *    the farm row IS the farmer record. [farmerName] / [farmerPhone] carry
     *    the person. When that decision is revisited, this is the one line that
     *    changes.
     */
    suspend fun createLocalForFarmer(
        farmerName: String,
        farmerPhone: String?,
        shambaName: String,
        registeredByAgentId: String?,
        cropType: String? = null,
        acres: Double? = null,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val farm = FarmEntity(
            id = id,
            name = shambaName,
            cropType = cropType,
            acres = acres,
            locationLat = null,
            locationLng = null,
            useGps = false,
            waterSource = null,
            referrerAgentId = null,
            farmerUserId = null,
            registeredByAgentId = registeredByAgentId,
            farmerName = farmerName,
            farmerPhone = farmerPhone,
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
                    // cropType / acres / useGps are NOT sent — the server's
                    // CreateFarmDto has no such keys and forbidNonWhitelisted
                    // turns each into a 400, which this loop retries for ever.
                    // Crop and acreage reach the server on the Field instead.
                    // registeredByAgentId is not sent either: the server derives
                    // it from the caller and ignores the body value.
                    val response = api.createFarm(FarmCreateRequest(
                        id = farm.id,
                        name = farm.name,
                        locationLat = farm.locationLat,
                        locationLng = farm.locationLng,
                        waterSource = farm.waterSource,
                        referrerAgentId = farm.referrerAgentId,
                        farmerUserId = farm.farmerUserId,
                        farmerName = farm.farmerName,
                        farmerPhone = farm.farmerPhone,
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
                            response.code() == 403 -> {
                                abandonForbidden(farm, serverBody, "create_403")
                                report.failed("403")
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
                    } else if (response.code() == 403) {
                        abandonForbidden(farm, response.errorBody()?.string(), "update_403")
                        report.failed("403")
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
                    } else if (response.code() == 403) {
                        abandonForbidden(farm, response.errorBody()?.string(), "delete_403")
                        report.failed("403")
                    } else {
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    override suspend fun pullSince(): Int =
        applyServerFarms(api.getFarms(since = farmDao.getLatestUpdatedAt(), includeDeleted = true))

    /**
     * Pull the register — `GET /farms?registeredBy=me` (KWAP-01 step 3), the
     * officer's and agent's own registrations.
     *
     * A SECOND PULL, NOT A REPLACEMENT FOR [pullSince]. The two answer different
     * questions ("farms I own" vs "farms I typed in") and neither is a superset:
     * this season they overlap almost entirely, because farmerUserId is left
     * null and so the registrar is also the owner, but that is a decision
     * (KWAP-STEP2-DECISIONS §2) rather than a property. The moment on-behalf
     * creation is switched on, a registration stops appearing in [pullSince] at
     * all — that is precisely the hole this endpoint was built to fill.
     *
     * Not wired into [SyncableRepository]: the background worker syncs what the
     * device owns, and a farmer's device would get a 403 here. The officer's
     * directory drives it explicitly.
     *
     * Throws on 403 — the caller is no longer an active officer or village
     * agent, which is terminal and worth saying out loud rather than showing as
     * an empty list.
     */
    suspend fun pullRegisteredByMe(): Int =
        applyServerFarms(
            api.getFarms(
                // Deliberately no `since`: the register is small (hundreds of
                // rows across a season) and a WAO opening the directory wants
                // the truth, not an increment computed from a MAX(updatedAt)
                // that the owner-scoped pull also writes to.
                includeDeleted = false,
                registeredBy = "me",
            )
        )

    /**
     * Fold a server page into Room. Shared by both pulls so the field mapping
     * exists once — the fields below are exactly the bug KWAP-01 §4.2③ predicted,
     * and having two copies of this mapping is how one of them goes stale.
     */
    private suspend fun applyServerFarms(serverFarms: List<FarmResponse>): Int {
        if (serverFarms.isEmpty()) return 0

        // The columns the server's Farm does not have. cropType and acres live
        // on the Field server-side and useGps is pure UI state, so a naive
        // rebuild nulls all three on every pull — invisibly, since the farmer
        // sees it only when their crop chip empties itself. Carry them forward.
        val existing = farmDao.getByIds(serverFarms.map { it.id }).associateBy { it.id }

        val localEntities = serverFarms.map { server ->
            val local = existing[server.id]
            FarmEntity(
                id = server.id,
                name = server.name,
                cropType = local?.cropType,
                acres = local?.acres,
                locationLat = server.locationLat,
                locationLng = server.locationLng,
                useGps = local?.useGps ?: false,
                waterSource = server.waterSource,
                referrerAgentId = server.referrerAgentId,
                // KWAP-01 §4.2③: map these or the officer's directory silently
                // empties itself on the next pull — a device bug that reads as a
                // server one. farmerUserId is the server's `userId`: the owner,
                // which is what the field has always meant.
                farmerUserId = server.userId,
                registeredByAgentId = server.registeredByAgentId,
                farmerName = server.farmerName,
                farmerPhone = server.farmerPhone,
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

    private suspend fun abandonForbidden(
        local: FarmEntity,
        serverPayload: String?,
        conflictType: String,
    ) {
        // 403 is TERMINAL and must clear pendingSync. The server answers 403 —
        // never 400 — for the on-behalf role and ward rejections precisely so
        // this branch exists to catch them; left pending, a permanently refused
        // row sits at the head of the queue and is re-sent on every sync cycle
        // for ever. Audited before it is dropped, so a wrongly-refused
        // registration is recoverable rather than merely gone.
        recordConflict(local, serverPayload ?: "{}", conflictType)
        farmDao.upsert(local.copy(pendingSync = false))
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
