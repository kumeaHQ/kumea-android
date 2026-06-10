package co.ke.kumea.data.repository

import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.OrderEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * P1-T3 money + allow-list behaviour at the repository boundary. unitPrice is a
 * Long in Room and a String on the wire; a server rejection (officer
 * agent_code) surfaces as OrderRejectedException and persists NOTHING locally.
 * Plain fakes — see FakeKumeaApi for why there is no mocking library here.
 */
class OrderRepositoryTest {

    private val aboveTwo53 = 9007199254740993L
    private val aboveTwo53Wire = "9007199254740993"

    private class FakeOrderDao : OrderDao {
        val upserts = mutableListOf<OrderEntity>()
        override fun getAllActive(): Flow<List<OrderEntity>> = flowOf(emptyList())
        override fun getActiveByFarmer(farmerId: String): Flow<List<OrderEntity>> = flowOf(emptyList())
        override suspend fun getPendingSync(): List<OrderEntity> = emptyList()
        override suspend fun getLatestUpdatedAt(): String? = null
        override suspend fun upsertAll(orders: List<OrderEntity>) {}
        override suspend fun upsert(order: OrderEntity) { upserts.add(order) }
        override suspend fun markSynced(orderId: String, serverUpdatedAt: String) {}
        override suspend fun markSyncedDelete(orderId: String, deletedAt: String) {}
    }

    private class NoOpConflictDao : SyncConflictDao {
        override suspend fun insert(conflict: SyncConflictEntity) {}
    }

    private fun orderResponse(req: OrderCreateRequest) = OrderResponse(
        id = req.id, farmerId = req.farmerId, agentCode = req.agentCode,
        dealerId = req.dealerId, sku = req.sku, qty = req.qty,
        unitPrice = req.unitPrice, channel = req.channel,
        paymentStatus = "pending", date = req.date, createdAt = "t", updatedAt = "t",
    )

    @Test
    fun `createOnline sends cents as a wire String and stores the Long verbatim`() = runBlocking {
        val dao = FakeOrderDao()
        var sent: OrderCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> {
                sent = order
                return Response.success(orderResponse(order))
            }
        }
        val repository = OrderRepository(dao, NoOpConflictDao(), api)

        val entity = repository.createOnline(
            farmerId = "farm-1", agentCode = "VA-NANDI-014", dealerId = null,
            sku = "BFX-100G", qty = 2, unitPrice = 100000L,
            channel = "agent", date = "2026-06-10T08:00:00Z",
        )

        // Wire value is the exact decimal string — never a JSON number.
        assertEquals("100000", sent?.unitPrice)
        assertEquals("agent", sent?.channel)
        assertEquals(100000L, entity.unitPrice) // Long, never Double
        val stored = dao.upserts.single()
        assertEquals(false, stored.pendingSync)
        assertEquals("VA-NANDI-014", stored.agentCode)
    }

    @Test
    fun `createOnline round-trips cents above 2^53 byte-for-byte`() = runBlocking {
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> =
                Response.success(orderResponse(order))
        }
        val repository = OrderRepository(FakeOrderDao(), NoOpConflictDao(), api)

        val entity = repository.createOnline(
            farmerId = "farm-1", agentCode = null, dealerId = null,
            sku = "BFX-500G", qty = 1, unitPrice = aboveTwo53,
            channel = "direct", date = "2026-06-10T08:00:00Z",
        )

        assertEquals(aboveTwo53, entity.unitPrice)
        // Routed through Double this would corrupt: proof the discipline matters.
        assertTrue(aboveTwo53Wire.toDouble().toLong() != aboveTwo53)
    }

    @Test
    fun `createOnline surfaces an officer rejection and persists nothing`() = runBlocking {
        val dao = FakeOrderDao()
        val officerBody =
            """{"code":"officer_cannot_sell","message":"An extension_officer can never be the commercial attribution on a sale. Officers may register farmers (referrer), never sell."}"""
        val api = object : FakeKumeaApi() {
            override suspend fun createOrder(order: OrderCreateRequest): Response<OrderResponse> =
                Response.error(400, officerBody.toResponseBody("application/json".toMediaType()))
        }
        val repository = OrderRepository(dao, NoOpConflictDao(), api)

        try {
            repository.createOnline(
                farmerId = "farm-1", agentCode = "EO-NANDI-001", dealerId = null,
                sku = "BFX-100G", qty = 1, unitPrice = 100000L,
                channel = "agent", date = "2026-06-10T08:00:00Z",
            )
            fail("Expected OrderRejectedException")
        } catch (e: OrderRejectedException) {
            assertTrue(e.message!!.contains("extension_officer"))
        }
        // The rejected order never lands in Room — the queue can't be poisoned.
        assertTrue(dao.upserts.isEmpty())
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
