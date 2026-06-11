package co.ke.kumea.data.repository

import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.OrderCreateRequest
import co.ke.kumea.data.remote.dto.OrderResponse
import co.ke.kumea.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * P1-T5 offline money + FK-guard behaviour at the repository boundary.
 *
 * The offline path is the new thing: a sale is saved with createLocal (Long
 * cents, pending CREATE) and pushed later, with the wire Long↔String conversion
 * happening only at push. The money canary proves cents above 2^53 survive that
 * round-trip byte-for-byte. The FK-guard proves a missing farmer (404) or
 * unsynced selling agent (400 agent_code_not_found) DEFERS the order (stays
 * pending, retried) rather than crashing or silently dropping it; a permanent
 * rejection (officer_cannot_sell) is recorded + cleared so it can't loop.
 *
 * Plain fakes — see FakeKumeaApi for why there is no mocking library here.
 */
class OrderRepositoryTest {

    private val aboveTwo53 = 9007199254740993L
    private val aboveTwo53Wire = "9007199254740993"

    private class FakeOrderDao : OrderDao {
        val rows = mutableMapOf<String, OrderEntity>()
        override fun getAllActive(): Flow<List<OrderEntity>> = flowOf(rows.values.toList())
        override fun getActiveByFarmer(farmerId: String): Flow<List<OrderEntity>> =
            flowOf(rows.values.filter { it.farmerId == farmerId })
        override suspend fun getPendingSync(): List<OrderEntity> =
            rows.values.filter { it.pendingSync }.sortedBy { it.updatedAt }
        override suspend fun getLatestUpdatedAt(): String? = rows.values.maxOfOrNull { it.updatedAt }
        override suspend fun upsertAll(orders: List<OrderEntity>) { orders.forEach { rows[it.id] = it } }
        override suspend fun upsert(order: OrderEntity) { rows[order.id] = order }
        override suspend fun markSynced(orderId: String, serverUpdatedAt: String) {
            rows[orderId]?.let {
                rows[orderId] = it.copy(pendingSync = false, syncAction = SyncAction.UPDATE, updatedAt = serverUpdatedAt)
            }
        }
        override suspend fun markSyncedDelete(orderId: String, deletedAt: String) {
            rows[orderId]?.let { rows[orderId] = it.copy(pendingSync = false, deletedAt = deletedAt) }
        }
    }

    private class RecordingConflictDao : SyncConflictDao {
        val inserts = mutableListOf<SyncConflictEntity>()
        override suspend fun insert(conflict: SyncConflictEntity) { inserts.add(conflict) }
    }

    private fun orderResponse(req: OrderCreateRequest) = OrderResponse(
        id = req.id, farmerId = req.farmerId, agentCode = req.agentCode,
        dealerId = req.dealerId, sku = req.sku, qty = req.qty,
        unitPrice = req.unitPrice, channel = req.channel,
        paymentStatus = "pending", date = req.date, createdAt = "t", updatedAt = "t2",
    )

    private fun errorBody(json: String) = json.toResponseBody("application/json".toMediaType())

    @Test
    fun `createLocal stores cents as a Long and marks the row pending CREATE`() = runBlocking {
        val dao = FakeOrderDao()
        val repository = OrderRepository(dao, RecordingConflictDao(), FakeKumeaApi())

        val id = repository.createLocal(
            farmerId = "farm-1", agentCode = "VA-NANDI-014", dealerId = null,
            sku = "BFX-100G", qty = 2, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        val stored = dao.rows.getValue(id)
        assertEquals(100000L, stored.unitPrice) // Long cents, never Double
        assertEquals("VA-NANDI-014", stored.agentCode)
        assertEquals("agent", stored.channel)
        assertTrue(stored.pendingSync)
        assertEquals(SyncAction.CREATE, stored.syncAction)
    }

    @Test
    fun `pushPending sends cents as a wire String and marks synced`() = runBlocking {
        val dao = FakeOrderDao()
        var sent: OrderCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> {
                sent = order
                return Response.success(orderResponse(order))
            }
        }
        val repository = OrderRepository(dao, RecordingConflictDao(), api)
        val id = repository.createLocal(
            farmerId = "farm-1", agentCode = "VA-NANDI-014", dealerId = null,
            sku = "BFX-100G", qty = 2, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        val report = repository.pushPending()

        assertEquals(1, report.found)
        assertEquals(1, report.attempted)
        assertEquals(1, report.succeeded)
        assertEquals(0, report.failed)
        // Wire value is the exact decimal string — never a JSON number.
        assertEquals("100000", sent?.unitPrice)
        assertEquals("agent", sent?.channel)
        assertFalse(dao.rows.getValue(id).pendingSync)
    }

    @Test
    fun `offline money round-trips cents above 2^53 byte-for-byte`() = runBlocking {
        val dao = FakeOrderDao()
        var sent: OrderCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> {
                sent = order
                return Response.success(orderResponse(order))
            }
        }
        val repository = OrderRepository(dao, RecordingConflictDao(), api)
        val id = repository.createLocal(
            farmerId = "farm-1", agentCode = null, dealerId = null,
            sku = "BFX-500G", qty = 1, unitPrice = aboveTwo53,
            channel = "direct", date = "2026-06-10T08:00:00Z",
        )

        repository.pushPending()

        // Stored Long and the wire String both survive the offline path intact.
        assertEquals(aboveTwo53, dao.rows.getValue(id).unitPrice)
        assertEquals(aboveTwo53Wire, sent?.unitPrice)
        // Routed through Double this would corrupt: proof the discipline matters.
        assertTrue(aboveTwo53Wire.toDouble().toLong() != aboveTwo53)
    }

    @Test
    fun `pushPending defers when the farmer is not on the server yet (404)`() = runBlocking {
        val dao = FakeOrderDao()
        val conflicts = RecordingConflictDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> =
                Response.error(404, errorBody("""{"statusCode":404,"message":"Farmer not found","error":"Not Found"}"""))
        }
        val repository = OrderRepository(dao, conflicts, api)
        val id = repository.createLocal(
            farmerId = "farm-not-synced", agentCode = "VA-NANDI-014", dealerId = null,
            sku = "BFX-100G", qty = 1, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        val report = repository.pushPending()

        // Deferred: nothing pushed, the row stays PENDING for the next cycle, and
        // it is NOT recorded as a conflict (a missing parent is not a conflict).
        // The report says WHY (deferred, with reason) — never silent.
        assertEquals(0, report.succeeded)
        assertEquals(0, report.attempted)
        assertEquals(1, report.deferred)
        assertEquals(0, report.failed)
        assertTrue(dao.rows.getValue(id).pendingSync)
        assertTrue(conflicts.inserts.isEmpty())
    }

    @Test
    fun `pushPending defers when the selling agent is not on the server yet (400 agent_code_not_found)`() = runBlocking {
        val dao = FakeOrderDao()
        val conflicts = RecordingConflictDao()
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> =
                Response.error(400, errorBody("""{"code":"agent_code_not_found","message":"agentCode must reference an existing agent."}"""))
        }
        val repository = OrderRepository(dao, conflicts, api)
        val id = repository.createLocal(
            farmerId = "farm-1", agentCode = "VA-NANDI-099", dealerId = null,
            sku = "BFX-100G", qty = 1, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        val report = repository.pushPending()

        assertEquals(0, report.succeeded)
        assertEquals(1, report.deferred)
        assertTrue("Order with an unsynced agent must stay pending", dao.rows.getValue(id).pendingSync)
        assertTrue(conflicts.inserts.isEmpty())
    }

    @Test
    fun `pushPending records and clears a permanent rejection (officer_cannot_sell)`() = runBlocking {
        val dao = FakeOrderDao()
        val conflicts = RecordingConflictDao()
        val officerBody =
            """{"code":"officer_cannot_sell","message":"An extension_officer can never be the commercial attribution on a sale."}"""
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> =
                Response.error(400, errorBody(officerBody))
        }
        val repository = OrderRepository(dao, conflicts, api)
        val id = repository.createLocal(
            farmerId = "farm-1", agentCode = "EO-NANDI-001", dealerId = null,
            sku = "BFX-100G", qty = 1, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        val report = repository.pushPending()

        // A permanent rejection must NOT loop forever: cleared + recorded, and
        // surfaced as failed (400) in the report — not deferred, not silent.
        assertEquals(0, report.succeeded)
        assertEquals(0, report.deferred)
        assertEquals(1, report.failed)
        assertEquals("400", report.failures.single())
        assertFalse(dao.rows.getValue(id).pendingSync)
        assertEquals(1, conflicts.inserts.size)
        assertEquals("create_rejected", conflicts.inserts.single().conflictType)
    }

    @Test
    fun `lineTotalCents stays Long and refuses to overflow`() {
        // KES 1,000/sachet × 250,000 sachets = 25,000,000,000 cents — past Int32.
        assertEquals(25_000_000_000L, Money.lineTotalCents(250_000, 100000L))
        try {
            Money.lineTotalCents(Int.MAX_VALUE, Long.MAX_VALUE / 2)
            fail("Expected ArithmeticException on overflow")
        } catch (e: ArithmeticException) {
            assertNull(null) // overflow refused, never wrapped
        }
    }
}
