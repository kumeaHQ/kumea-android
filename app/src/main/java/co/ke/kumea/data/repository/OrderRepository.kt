package co.ke.kumea.data.repository

import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.OrderCreateRequest
import co.ke.kumea.data.remote.dto.OrderUpdateRequest
import co.ke.kumea.data.sync.SyncableRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A create the server explicitly rejected (400/404/409) — not a network error. */
class OrderRejectedException(message: String) : Exception(message)

/**
 * Order sync (P1-T3) — the Note copy one level shallower (Order belongs to
 * Farm), with one deliberate difference: **T3 creates orders ONLINE via
 * createOnline()**. The SyncableRepository contract (pushPending/pullSince) is
 * implemented and ready, but this repository is intentionally NOT bound into
 * RepositoryModule's Set and no refresh loop calls it — offline-first wiring
 * for orders is T5. pendingSync/syncAction exist in the entity so T5 is a
 * binding + UI change, not a schema change.
 *
 * MONEY: unitPrice is a Long of integer cents in the entity; the Long↔String
 * conversion is done HERE and only here (toString() out, toLong() in). Never
 * Double anywhere.
 *
 * THE OFFICER ALLOW-LIST: the server rejects an extension_officer agentCode
 * (service guard + DB trigger). createOnline surfaces that rejection as an
 * OrderRejectedException with the server's message — never swallowed, and the
 * rejected order is NOT persisted locally.
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

    /**
     * Create an order ONLINE (the T3 path): POST to the server first, persist
     * the canonical server row in Room only on success. A server rejection
     * (officer agentCode, bad channel, zero qty/price) throws
     * OrderRejectedException with the server's message; a network failure
     * throws as-is. Nothing is written locally on any failure — the pending
     * queue can't be poisoned by a permanently-rejected order.
     *
     * unitPrice is already-parsed integer cents (Long) the caller validated via
     * Money.parseToCents — converted to the wire String here, never re-parsed
     * from a float.
     */
    suspend fun createOnline(
        farmerId: String,
        agentCode: String?,
        dealerId: String?,
        sku: String,
        qty: Int,
        unitPrice: Long,
        channel: String,
        date: String,
    ): OrderEntity {
        val id = UUID.randomUUID().toString()
        val response = api.createOrder(
            OrderCreateRequest(
                id = id,
                farmerId = farmerId,
                agentCode = agentCode,
                dealerId = dealerId,
                sku = sku,
                qty = qty,
                // Long → wire String. Never a JSON number.
                unitPrice = unitPrice.toString(),
                channel = channel,
                date = date,
            )
        )
        if (!response.isSuccessful) {
            throw OrderRejectedException(
                parseApiError(response.errorBody()?.string(), response.code()),
            )
        }
        val server = response.body()!!
        val entity = OrderEntity(
            id = server.id,
            farmerId = server.farmerId,
            agentCode = server.agentCode,
            dealerId = server.dealerId,
            sku = server.sku,
            qty = server.qty,
            // wire String → Long. Never Double.
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
        orderDao.upsert(entity)
        return entity
    }

    /**
     * Push all pending local changes to the server. Implemented for the
     * SyncableRepository contract; nothing enqueues orders as pending until T5
     * wires offline-first creation.
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
                            unitPrice = order.unitPrice.toString(),
                            channel = order.channel,
                            paymentStatus = order.paymentStatus,
                            date = order.date,
                        )
                    )
                    if (response.isSuccessful) {
                        orderDao.markSynced(order.id, response.body()!!.updatedAt)
                        pushed++
                    } else if (response.code() == 409) {
                        recordConflict(order, response.errorBody()?.string() ?: "{}", "create_409")
                        orderDao.upsert(order.copy(pendingSync = false))
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
        // hasn't reconciled yet.
        val pendingIds = orderDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            orderDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
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

    /**
     * Extract a human-readable message from a NestJS error body. Two shapes:
     * {"code":"officer_cannot_sell","message":"..."} (service guards) and
     * {"message":["qty must not be less than 1"],...} (class-validator).
     * Malformed/missing bodies fall back to the HTTP code — never thrown away.
     */
    private fun parseApiError(body: String?, httpCode: Int): String {
        if (body.isNullOrBlank()) return "Order rejected (HTTP $httpCode)"
        return try {
            val message = Json.parseToJsonElement(body).jsonObject["message"]
            when {
                message == null -> "Order rejected (HTTP $httpCode)"
                message is kotlinx.serialization.json.JsonArray ->
                    message.jsonArray.firstOrNull()?.jsonPrimitive?.content
                        ?: "Order rejected (HTTP $httpCode)"
                else -> message.jsonPrimitive.content
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            "Order rejected (HTTP $httpCode): $body"
        } catch (e: IllegalArgumentException) {
            "Order rejected (HTTP $httpCode): $body"
        }
    }
}
