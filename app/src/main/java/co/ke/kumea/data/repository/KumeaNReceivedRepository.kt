package co.ke.kumea.data.repository

import co.ke.kumea.data.local.KumeaNReceivedDao
import co.ke.kumea.data.local.KumeaNReceivedEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.KumeaNReceivedCreateRequest
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncableRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "This farmer received Kumea N" — the interim shim (KWAP-03 §7).
 *
 * Bound into `Set<SyncableRepository>` and syncing, as of the KWAP-03 server
 * deploy. Its `pullSince()` therefore runs on EVERY device on every cycle,
 * whatever persona is signed in, which is why the server's GET is scoped by farm
 * visibility rather than gated on role — see the note on [pullSince].
 *
 * What the rows are for: Zone 1 of the farm page, so a farmer can see what they
 * were given, and a structured record the KWAP-02 backfill can turn into a
 * `stock_distributions` entry with a script rather than an excavation.
 */
@Singleton
class KumeaNReceivedRepository @Inject constructor(
    private val dao: KumeaNReceivedDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {

    /** Zone 1 of the farm page: what this farmer received, newest first. */
    fun getActiveByFarm(farmId: String): Flow<List<KumeaNReceivedEntity>> =
        dao.getActiveByFarm(farmId)

    /**
     * Record a handover.
     *
     * [recordedByAgentId] is non-null in the signature, not merely documented as
     * derived: the whole value of recording this now rather than as a free-text
     * note is that the KWAP-02 backfill can attribute every distribution, and a
     * row with no recorder cannot be attributed at all. The caller derives it
     * from the signed-in agent — it is never picked from a list, the same
     * derive-don't-check rule as ward.
     *
     * There is NO price parameter and there must never be one. This is a
     * research handover of free product; the commission engine is live and
     * backdated to 1 June.
     */
    suspend fun createLocal(
        farmId: String,
        strainCode: String,
        packSizeG: Int,
        batchNumber: String,
        qty: Int,
        occurredAt: String,
        recordedByAgentId: String,
    ): String {
        require(qty > 0) { "a handover of zero sachets is not a handover" }
        require(batchNumber.isNotBlank()) {
            // The 13 Aug lock: every distribution record carries its batch.
            // Without it the row cannot be reconciled at season end, which is
            // the entire reason this is a table rather than a note.
            "batch number is required — see KWAP-03 §7"
        }
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        dao.upsert(
            KumeaNReceivedEntity(
                id = id,
                farmId = farmId,
                strainCode = strainCode,
                packSizeG = packSizeG,
                batchNumber = batchNumber.trim(),
                qty = qty,
                occurredAt = occurredAt,
                recordedByAgentId = recordedByAgentId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                pendingSync = true,
                syncAction = SyncAction.CREATE,
            )
        )
        return id
    }

    override suspend fun pushPending(): PushReport {
        val pending = dao.getPendingSync()
        val report = PushReportBuilder("Kumea N received")
        report.found = pending.size
        for (record in pending) {
            when (record.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createKumeaNReceived(
                        KumeaNReceivedCreateRequest(
                            id = record.id,
                            farmId = record.farmId,
                            strainCode = record.strainCode,
                            packSizeG = record.packSizeG,
                            batchNumber = record.batchNumber,
                            qty = record.qty,
                            occurredAt = record.occurredAt,
                            // recordedByAgentId is NOT sent: the server derives
                            // it from the caller and would reject the key.
                        )
                    )
                    when {
                        response.isSuccessful -> {
                            dao.markSynced(record.id, response.body()!!.updatedAt)
                            report.succeeded()
                        }
                        response.code() == 409 -> {
                            recordConflict(record, response.errorBody()?.string() ?: "{}", "create_409")
                            dao.upsert(record.copy(pendingSync = false))
                            report.failed("409")
                        }
                        response.code() == 403 -> {
                            // Terminal, like FarmRepository's: the caller is no
                            // longer an active agent. Audited, then dropped from
                            // the queue rather than retried for ever.
                            recordConflict(record, response.errorBody()?.string() ?: "{}", "create_403")
                            dao.upsert(record.copy(pendingSync = false))
                            report.failed("403")
                        }
                        else -> report.failed(response.code().toString())
                    }
                }
                // A handover is a fact about a moment; it is not edited. The UI
                // offers no edit path, so an UPDATE row can only come from
                // markSynced's action flip, which also clears pendingSync.
                SyncAction.UPDATE -> report.failed("unexpected_update_row")
                SyncAction.DELETE -> {
                    val response = api.deleteKumeaNReceived(record.id)
                    if (response.isSuccessful) {
                        dao.markSyncedDelete(record.id, record.deletedAt ?: Clock.System.now().toString())
                        report.succeeded()
                    } else {
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    /**
     * THIS RUNS ON EVERY DEVICE. `SyncWorker` iterates the whole
     * `Set<SyncableRepository>` inside one try block, so anything thrown here
     * aborts that entire cycle — every later repository skipped, three retries,
     * then a sync-failure notification in front of the user.
     *
     * `getKumeaNReceived` returns a bare `List<T>` rather than `Response<T>`, so
     * any non-2xx throws. The server's GET is scoped by farm visibility instead
     * of gated on role precisely so a farmer-persona handset — which has nothing
     * to fetch here — gets 200 and an empty list rather than a 403.
     */
    override suspend fun pullSince(): Int {
        val server = api.getKumeaNReceived(since = dao.getLatestUpdatedAt(), includeDeleted = true)
        if (server.isEmpty()) return 0

        val pendingIds = dao.getPendingSync().map { it.id }.toSet()
        val clean = server
            .filter { it.id !in pendingIds }
            .map { row ->
                KumeaNReceivedEntity(
                    id = row.id,
                    farmId = row.farmId,
                    strainCode = row.strainCode,
                    packSizeG = row.packSizeG,
                    batchNumber = row.batchNumber,
                    qty = row.qty,
                    occurredAt = row.occurredAt,
                    // Server-authoritative; the local value was only ever a
                    // stand-in so the row could appear before it synced.
                    recordedByAgentId = row.recordedByAgentId.orEmpty(),
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    deletedAt = row.deletedAt,
                    pendingSync = false,
                    syncAction = SyncAction.UPDATE,
                )
            }
        if (clean.isNotEmpty()) dao.upsertAll(clean)
        return clean.size
    }

    private suspend fun recordConflict(
        local: KumeaNReceivedEntity,
        serverPayload: String,
        conflictType: String,
    ) {
        syncConflictDao.insert(
            SyncConflictEntity(
                id = UUID.randomUUID().toString(),
                entityType = "kumea_n_received",
                entityId = local.id,
                localPayload = local.toString(),
                serverPayload = serverPayload,
                conflictType = conflictType,
                occurredAt = Clock.System.now().toString(),
            )
        )
    }
}
