package co.ke.kumea.data.repository

import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.OrderCreateRequest
import co.ke.kumea.data.remote.dto.OrderUpdateRequest
import co.ke.kumea.data.remote.parseErrorCode
import co.ke.kumea.data.sync.SyncableRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Order sync (P1-T3 → offline-first in P1-T5) — the Note copy one level
 * shallower (an Order belongs to a Farm). T3 created orders online-only; T5
 * makes it offline-first like every other entity: createLocal() saves the order
 * to Room as a pending CREATE, and SyncWorker pushes it later via the
 * SyncableRepository contract. The entity already carries pendingSync/syncAction,
 * so this was a binding + UI change, not a schema change.
 *
 * MONEY: unitPrice is a Long of integer cents in the entity; the Long↔String
 * conversion is done HERE and only here (toString() out, toLong() in). Never
 * Double anywhere — values above 2^53 cents survive byte-for-byte.
 *
 * FK-GUARD (the real safety net, not Set iteration order): an Order reads from
 * Farm (farmerId) and resolves an Agent (agentCode). If either parent hasn't
 * synced yet, pushPending() DEFERS the order — leaves pendingSync=true, skips it
 * this cycle, retries next cycle once the parent lands. A deferral is never a
 * silent skip: the row stays visibly PENDING and the pushed count reflects it.
 *
 * THE OFFICER ALLOW-LIST is enforced server-side (service guard + DB trigger):
 * an extension_officer agentCode is rejected. The create screen's agent picker
 * already excludes officers, so this is a backstop — a permanent rejection is
 * recorded and cleared (never looped) rather than poisoning the pending queue.
 */
@Singleton
class OrderRepository @Inject constructor(
    private val orderDao: OrderDao,
    private val syncConflictDao: SyncConflictDao,
    private val api: KumeaApi,
) : SyncableRepository {

    /** Observe all active orders (live, via Room Flow). */
    fun getAllActive(): Flow<List<OrderEntity>> = orderDao.getAllActive()

    /** Observe active orders for a single farmer (farm). */
    fun getActiveByFarmer(farmerId: String): Flow<List<OrderEntity>> =
        orderDao.getActiveByFarmer(farmerId)

    /** How many orders are still waiting to push — lets a caller surface FK deferrals. */
    suspend fun countPendingSync(): Int = orderDao.getPendingSync().size

    /**
     * Record a sale OFFLINE-FIRST (the T5 path): save to Room as a pending
     * CREATE and return the generated UUID. SyncWorker pushes it later. This is
     * the same shape as FarmRepository/NoteRepository.createLocal — no network
     * call here, so a sale recorded in airplane mode lands immediately.
     *
     * unitPrice is already-parsed integer cents (Long) the caller validated via
     * Money.parseToCents — stored verbatim, converted to the wire String only at
     * push time, never re-parsed from a float.
     */
    suspend fun createLocal(
        farmerId: String,
        agentCode: String?,
        dealerId: String?,
        sku: String,
        qty: Int,
        unitPrice: Long,
        channel: String,
        date: String,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val order = OrderEntity(
            id = id,
            farmerId = farmerId,
            agentCode = agentCode,
            dealerId = dealerId,
            sku = sku,
            qty = qty,
            unitPrice = unitPrice,
            channel = channel,
            // Server default; the canonical value is reconciled on the next pull.
            paymentStatus = "pending",
            date = date,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            pendingSync = true,
            syncAction = SyncAction.CREATE,
        )
        orderDao.upsert(order)
        return id
    }

    /**
     * Push all pending local changes to the server. CREATE applies the FK-guard:
     * a 404 (farmer not on server) or 400 agent_code_not_found (selling agent not
     * on server) DEFERS the order for a later cycle; a 409 is server-wins; any
     * other rejection is permanent (recorded + cleared, never looped). A network
     * failure propagates so WorkManager / the refresh caller retries with backoff
     * (CancellationException included — never swallowed).
     *
     * Offline UPDATE/DELETE of a pending order is out of scope for P1-T5
     * (CREATE-only); those branches stay as the T3 contract left them.
     */
    override suspend fun pushPending(): Int {
        val pending = orderDao.getPendingSync()
        var pushed = 0
        for (order in pending) {
            when (order.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createOrder(
                        OrderCreateRequest(
                            id = order.id,
                            farmerId = order.farmerId,
                            agentCode = order.agentCode,
                            dealerId = order.dealerId,
                            sku = order.sku,
                            qty = order.qty,
                            // Long → wire String. Never a JSON number.
                            unitPrice = order.unitPrice.toString(),
                            channel = order.channel,
                            paymentStatus = order.paymentStatus,
                            date = order.date,
                        )
                    )
                    if (response.isSuccessful) {
                        orderDao.markSynced(order.id, response.body()!!.updatedAt)
                        pushed++
                    } else {
                        // errorBody().string() consumes the buffer — read it once.
                        val body = response.errorBody()?.string()
                        when {
                            isFkParentMissing(response.code(), body) -> {
                                // DEFER: farmer or selling agent not on the server
                                // yet. Leave pendingSync=true; the next cycle retries
                                // once the parent lands. Not a silent skip — the row
                                // stays PENDING and is re-pushed (FK ordering safety
                                // net, independent of Set iteration order).
                            }
                            response.code() == 409 -> {
                                recordConflict(order, body ?: "{}", "create_409")
                                orderDao.upsert(order.copy(pendingSync = false))
                            }
                            else -> {
                                // Permanent rejection (officer_cannot_sell, validation).
                                // Record + clear so a barred order can't loop forever;
                                // the UI already prevents these from being created.
                                recordConflict(order, body ?: "{}", "create_rejected")
                                orderDao.upsert(order.copy(pendingSync = false))
                            }
                        }
                    }
                }
                SyncAction.UPDATE -> {
                    val response = api.updateOrder(
                        order.id,
                        OrderUpdateRequest(
                            agentCode = order.agentCode,
                            dealerId = order.dealerId,
                            sku = order.sku,
                            qty = order.qty,
                            unitPrice = order.unitPrice.toString(),
                            channel = order.channel,
                            paymentStatus = order.paymentStatus,
                            date = order.date,
                            updatedAt = order.updatedAt,
                        )
                    )
                    if (response.isSuccessful) {
                        orderDao.markSynced(order.id, response.body()!!.updatedAt)
                        pushed++
                    } else if (response.code() == 409) {
                        recordConflict(order, response.errorBody()?.string() ?: "{}", "update_409")
                        orderDao.upsert(order.copy(pendingSync = false))
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deleteOrder(order.id)
                    if (response.isSuccessful) {
                        val now = Clock.System.now().toString()
                        orderDao.markSyncedDelete(order.id, order.deletedAt ?: now)
                        pushed++
                    }
                }
            }
        }
        return pushed
    }

    /**
     * Pull server changes since the latest local updatedAt. Must run AFTER the
     * farm pull in a sync cycle: an order's CASCADE foreign key requires its
     * parent farm row to exist locally first. unitPrice is parsed from the wire
     * String to Long here — never via Double, so values above 2^53 survive.
     */
    override suspend fun pullSince(): Int {
        val since = orderDao.getLatestUpdatedAt()
        // includeDeleted = true so soft-deleted rows reconcile on other devices.
        val serverOrders = api.getOrders(since = since, includeDeleted = true)

        if (serverOrders.isEmpty()) return 0

        val localEntities = serverOrders.map { server ->
            OrderEntity(
                id = server.id,
                farmerId = server.farmerId,
                agentCode = server.agentCode,
                dealerId = server.dealerId,
                sku = server.sku,
                qty = server.qty,
                unitPrice = server.unitPrice.toLong(),
                channel = server.channel,
                paymentStatus = server.paymentStatus,
                date = server.date,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // Same invariant as Note/Farm: never let pull clobber a row that push
        // hasn't reconciled yet (e.g. a deferred order still waiting on its FK parent).
        val pendingIds = orderDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            orderDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
    }

    /**
     * The order's FK parent isn't on the server yet → defer + retry, never a hard
     * fail. Two signals from the OrdersService contract:
     *   • 404 "Farmer not found" — the Farm (farmerId) hasn't synced (assertFarmOwned).
     *   • 400 agent_code_not_found — the selling Agent hasn't synced (assertSaleAttribution).
     * Everything else (officer_cannot_sell, validation, 409) is NOT a deferral.
     */
    private fun isFkParentMissing(httpCode: Int, errorBody: String?): Boolean {
        if (httpCode == 404) return true
        if (httpCode == 400 && parseErrorCode(errorBody) == "agent_code_not_found") return true
        return false
    }

    private suspend fun recordConflict(local: OrderEntity, serverPayload: String, conflictType: String) {
        val entity = SyncConflictEntity(
            id = UUID.randomUUID().toString(),
            entityType = "order",
            entityId = local.id,
            localPayload = local.toString(),
            serverPayload = serverPayload,
            conflictType = conflictType,
            occurredAt = Clock.System.now().toString(),
        )
        syncConflictDao.insert(entity)
    }
}
