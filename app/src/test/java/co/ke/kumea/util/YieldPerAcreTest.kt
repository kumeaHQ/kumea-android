package co.ke.kumea.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The §2.8 sanity line's arithmetic, including the worked example. */
class YieldPerAcreTest {

    @Test
    fun `the ticket's worked example`() {
        // 8 bags @ 90 kg = 720 kg, on 1.6 planted acres → 450 kg/acre.
        val qtyCenti = 800L
        val kgCenti = YieldConversion.toKgCenti(qtyCenti, 9_000L)
        assertEquals(72_000L, kgCenti)

        val perAcre = YieldConversion.kgPerAcreCenti(kgCenti, 160L)
        assertEquals(45_000L, perAcre)
        assertEquals("450", Quantity.formatCenti(perAcre!!))
    }

    @Test
    fun `no planting record means no per-acre line, not a crash`() {
        assertNull(YieldConversion.kgPerAcreCenti(72_000L, 0L))
        assertNull(YieldConversion.kgPerAcreCenti(72_000L, -1L))
    }

    @Test
    fun `planted area is the divisor, and it is not the farm's size`() {
        // The distinction §2.4 exists for. A farmer with a 3-acre shamba who
        // planted 1.6 acres and harvested 720 kg got 450 kg/acre, not 240 —
        // dividing by farm size would understate exactly the farmers who were
        // most cautious about trying the product.
        val kgCenti = 72_000L
        assertEquals(45_000L, YieldConversion.kgPerAcreCenti(kgCenti, 160L))
        assertEquals(24_000L, YieldConversion.kgPerAcreCenti(kgCenti, 300L))
    }

    @Test
    fun `the multiply happens before the divide, so precision survives`() {
        // 100 kg on 0.03 acres. Dividing first (0 kg/acre) would lose the whole
        // figure; multiplying first keeps it.
        assertEquals(333_333L, YieldConversion.kgPerAcreCenti(10_000L, 3L))
    }

    @Test
    fun `bags with no stated size cannot be converted and stay at zero`() {
        // The v13 rule, unchanged: a bag is 50 or 90 kg, so there is no default.
        assertNull(YieldConversion.defaultFactorCenti("bags"))
        assertEquals(100L, YieldConversion.defaultFactorCenti("kg"))
        assertEquals(200L, YieldConversion.defaultFactorCenti("gorogoro"))
    }
}
