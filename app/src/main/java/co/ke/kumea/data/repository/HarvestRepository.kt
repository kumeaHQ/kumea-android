package co.ke.kumea.data.repository

import co.ke.kumea.data.local.ConversionSource
import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.HarvestDao
import co.ke.kumea.data.local.HarvestEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.HarvestCreateRequest
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncableRepository
import co.ke.kumea.util.Quantity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first Harvest sync (Build-2) — the SyncableRepository pattern, cloned
 * from FieldRepository per the 3.1 discipline. Differences from Field:
 *   - parent is Field (FK guard on fieldId; harvest pulls after fields)
 *   - quantities cross the wire as decimal strings via Quantity centi-conversion
 *   - ONE row per completed wizard — createLocal is the only write path the UI
 *     uses, so a partial harvest can never exist, let alone sync
 */
@Singleton
class HarvestRepository @Inject constructor(
    private val harvestDao: HarvestDao,
    private val fieldDao: FieldDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {

    fun getActiveByField(fieldId: String): Flow<List<HarvestEntity>> =
        harvestDao.getActiveByField(fieldId)

    /** Atomic single-record save at the end of the wizard. Returns the UUID. */
    suspend fun createLocal(
        fieldId: String,
        harvestDate: String,
        quantityCenti: Long,
        unit: String,
        qtyKgCenti: Long,
        conversionFactorCenti: Long,
        conversionSource: String,
        keptCenti: Long?,
        soldCenti: Long?,
        replantIntent: String,
        replantMonth: String?,
    ): String {
        require(quantityCenti > 0) { "quantity must be positive" }
        // The canonical figure is REQUIRED, not defaulted. Every yield
        // calculation in the impact report reads qtyKgCenti and only it, so a
        // harvest that reaches Room with a zero here is a row that silently
        // contributes nothing to the season's headline number. The wizard
        // cannot produce one — the UNIT step refuses to advance past bags
        // without a size — and this refuses to store one.
        require(qtyKgCenti > 0) { "harvest needs canonical kilograms — see KWAP-03 §4.4" }
        require(conversionFactorCenti > 0) { "a conversion factor must be recorded, not assumed later" }
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        harvestDao.upsert(
            HarvestEntity(
                id = id,
                fieldId = fieldId,
                harvestDate = harvestDate,
                quantityCenti = quantityCenti,
                unit = unit,
                keptCenti = keptCenti,
                soldCenti = soldCenti,
                replantIntent = replantIntent,
                replantMonth = replantMonth,
                qtyKgCenti = qtyKgCenti,
                conversionFactorCenti = conversionFactorCenti,
                conversionSource = conversionSource,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                pendingSync = true,
                syncAction = SyncAction.CREATE,
            )
        )
        return id
    }

    /** Soft-delete (offline-first); "newest record is truth" edit model. */
    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        val harvest = harvestDao.getPendingSync().find { it.id == id } ?: return
        harvestDao.upsert(
            harvest.copy(deletedAt = now, updatedAt = now, pendingSync = true, syncAction = SyncAction.DELETE)
        )
    }

    override suspend fun pushPending(): PushReport {
        val pending = harvestDao.getPendingSync()
        val report = PushReportBuilder("Harvests")
        report.found = pending.size
        for (harvest in pending) {
            when (harvest.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createHarvest(
                        HarvestCreateRequest(
                            id = harvest.id,
                            fieldId = harvest.fieldId,
                            harvestDate = harvest.harvestDate,
                            quantity = Quantity.formatCenti(harvest.quantityCenti),
                            unit = harvest.unit,
                            keptQuantity = harvest.keptCenti?.let(Quantity::formatCenti),
                            soldQuantity = harvest.soldCenti?.let(Quantity::formatCenti),
                            replantIntent = harvest.replantIntent,
                            replantMonth = harvest.replantMonth,
                        )
                    )
                    if (response.isSuccessful) {
                        harvestDao.markSynced(harvest.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else if (response.code() == 409) {
                        recordConflict(harvest, response.errorBody()?.string() ?: "{}", "create_409")
                        harvestDao.upsert(harvest.copy(pendingSync = false))
                        report.failed("409")
                    } else {
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.UPDATE -> {
                    // The UI has no harvest-edit path yet ("newest record is
                    // truth"); an UPDATE row can only come from markSynced's
                    // action flip, which also clears pendingSync. Surfaced, not
                    // silently skipped, if one ever appears.
                    report.failed("unexpected_update_row")
                }
                SyncAction.DELETE -> {
                    val response = api.deleteHarvest(harvest.id)
                    if (response.isSuccessful) {
                        val now = Clock.System.now().toString()
                        harvestDao.markSyncedDelete(harvest.id, harvest.deletedAt ?: now)
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
        val since = harvestDao.getLatestUpdatedAt()
        val serverHarvests = api.getHarvests(since = since, includeDeleted = true)
        if (serverHarvests.isEmpty()) return 0

        // The canonical-kilogram columns are device-only until the KWAP-03
        // server patch deploys and HarvestResponse carries them, so a rebuild
        // from the server would zero them — and a zeroed qtyKgCenti is a harvest
        // that contributes nothing to the impact report while looking perfectly
        // normal on screen. Carried forward, exactly as farms carries its own.
        // WHEN THE SERVER PATCH LANDS, these move to `server.x` in the same
        // commit that adds them to HarvestResponse (§8).
        val existing = harvestDao.getByIds(serverHarvests.map { it.id }).associateBy { it.id }

        val localEntities = serverHarvests.mapNotNull { server ->
            // Quantities arrive as decimal strings; an unparseable value is a
            // contract violation — skip the row and let it surface in counts
            // rather than storing a corrupted quantity.
            val quantityCenti = Quantity.parseToCenti(server.quantity) ?: return@mapNotNull null
            val local = existing[server.id]
            HarvestEntity(
                id = server.id,
                fieldId = server.fieldId,
                harvestDate = server.harvestDate,
                quantityCenti = quantityCenti,
                unit = server.unit,
                keptCenti = server.keptQuantity?.let(Quantity::parseToCenti),
                soldCenti = server.soldQuantity?.let(Quantity::parseToCenti),
                replantIntent = server.replantIntent,
                replantMonth = server.replantMonth,
                qtyKgCenti = local?.qtyKgCenti ?: 0,
                conversionFactorCenti = local?.conversionFactorCenti ?: 0,
                conversionSource = local?.conversionSource ?: ConversionSource.UNKNOWN,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // FK guard (same as Field→Farm): only upsert harvests whose parent field
        // exists locally. Field pull runs before harvest pull in the Set cycle;
        // correctness comes from this guard, not iteration order.
        val localFieldIds = fieldDao.getAllIds().toSet()
        val owned = localEntities.filter { it.fieldId in localFieldIds }
        val pendingIds = harvestDao.getPendingSync().map { it.id }.toSet()
        val clean = owned.filter { it.id !in pendingIds }
        if (clean.isNotEmpty()) {
            harvestDao.upsertAll(clean)
        }
        return clean.size
    }

    private suspend fun recordConflict(local: HarvestEntity, serverPayload: String, conflictType: String) {
        syncConflictDao.insert(
            SyncConflictEntity(
                id = UUID.randomUUID().toString(),
                entityType = "harvest",
                entityId = local.id,
                localPayload = local.toString(),
                serverPayload = serverPayload,
                conflictType = conflictType,
                occurredAt = Clock.System.now().toString(),
            )
        )
    }
}
