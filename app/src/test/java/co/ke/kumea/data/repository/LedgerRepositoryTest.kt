package co.ke.kumea.data.repository

import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.CostCategoryLineResponse
import co.ke.kumea.data.remote.dto.FarmLedgerResponse
import co.ke.kumea.data.remote.dto.FieldLedgerLineResponse
import co.ke.kumea.data.remote.dto.FieldLedgerResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Ticket 3.3 — the Ledger's money crosses the wire as Strings and must land as a
 * **signed Long**, never a Double. net can be negative, and a total above 2^53
 * must survive byte-for-byte. Plain fakes (see FakeKumeaApi for why there is no
 * mocking library here).
 */
class LedgerRepositoryTest {

    // 2^53 + 1 cents = KES 90,071,992,547,409.93.
    private val aboveTwo53 = 9007199254740993L
    private val aboveTwo53Wire = "9007199254740993"

    @Test
    fun `farm ledger parses signed cents to Long, including a negative field net`() = runBlocking {
        val api = object : FakeKumeaApi() {
            override suspend fun getFarmLedger(farmId: String) = FarmLedgerResponse(
                farmId = farmId,
                currency = "KES",
                totalRevenueCents = "450000",
                totalCostCents = "320000",
                netCents = "130000",
                byField = listOf(
                    FieldLedgerLineResponse("f1", "North paddock", "450000", "200000", "250000"),
                    // A loss field — netCents is negative on the wire.
                    FieldLedgerLineResponse("f2", "Main field", "0", "120000", "-120000"),
                ),
            )
        }
        val repository = LedgerRepository(api)

        val ledger = repository.getFarmLedger("farm-1")

        assertEquals(450000L, ledger.revenueCents)
        assertEquals(320000L, ledger.costCents)
        assertEquals(130000L, ledger.netCents)
        assertEquals(2, ledger.byField.size)
        assertEquals(250000L, ledger.byField[0].netCents)
        // Negative net survives as a signed Long (never a Double).
        assertEquals(-120000L, ledger.byField[1].netCents)
    }

    @Test
    fun `field ledger parses a wire String above 2^53 back to the exact Long`() = runBlocking {
        val api = object : FakeKumeaApi() {
            override suspend fun getFieldLedger(fieldId: String) = FieldLedgerResponse(
                fieldId = fieldId,
                fieldName = "Bumper",
                currency = "KES",
                totalRevenueCents = aboveTwo53Wire,
                totalCostCents = "0",
                netCents = aboveTwo53Wire,
            )
        }
        val repository = LedgerRepository(api)

        val ledger = repository.getFieldLedger("field-1")

        assertEquals(aboveTwo53, ledger.revenueCents)
        assertEquals(aboveTwo53, ledger.netCents)
        // Proof the discipline matters: through a Double this would corrupt.
        @Suppress("DEPRECATION")
        val viaDouble = aboveTwo53Wire.toDouble().toLong()
        assertNotEquals(aboveTwo53, viaDouble)
    }

    // ── Ticket 2.1: byCostCategory ────────────────────────────────────────────

    @Test
    fun `farm ledger parses byCostCategory to enum + signed Long, incl the uncategorised bucket`() = runBlocking {
        val api = object : FakeKumeaApi() {
            override suspend fun getFarmLedger(farmId: String) = FarmLedgerResponse(
                farmId = farmId,
                currency = "KES",
                totalRevenueCents = "0",
                totalCostCents = "290000",
                netCents = "-290000",
                byCostCategory = listOf(
                    CostCategoryLineResponse("SEED", "200000"),
                    CostCategoryLineResponse("FERTILISER", "50000"),
                    CostCategoryLineResponse(null, "40000"), // uncategorised bucket
                ),
            )
        }
        val repository = LedgerRepository(api)

        val ledger = repository.getFarmLedger("farm-1")

        assertEquals(3, ledger.byCostCategory.size)
        assertEquals(CostCategory.SEED, ledger.byCostCategory[0].category)
        assertEquals(200000L, ledger.byCostCategory[0].costCents)
        // null category = uncategorised, cents still a signed Long.
        assertEquals(null, ledger.byCostCategory[2].category)
        assertEquals(40000L, ledger.byCostCategory[2].costCents)
        // The breakdown reconciles to the cost total.
        assertEquals(290000L, ledger.byCostCategory.sumOf { it.costCents })
    }

    @Test
    fun `field ledger parses a byCostCategory bucket above 2^53 to the exact Long`() = runBlocking {
        val api = object : FakeKumeaApi() {
            override suspend fun getFieldLedger(fieldId: String) = FieldLedgerResponse(
                fieldId = fieldId,
                fieldName = "Bumper",
                currency = "KES",
                totalRevenueCents = "0",
                totalCostCents = aboveTwo53Wire,
                netCents = "-$aboveTwo53Wire",
                byCostCategory = listOf(CostCategoryLineResponse("SEED", aboveTwo53Wire)),
            )
        }
        val repository = LedgerRepository(api)

        val ledger = repository.getFieldLedger("field-1")

        assertEquals(aboveTwo53, ledger.byCostCategory.single().costCents)
    }
}
