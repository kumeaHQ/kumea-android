package co.ke.kumea.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The locked price matrix (PRICE-MATRIX-LOCKED.md, 12 Aug), pinned.
 *
 * These are POLICY numbers, not computed ones. They get spoken aloud in a shop
 * and they feed a commission engine that is live and backdated to 1 June, so a
 * silent change to any of them is a change to what a farmer pays and what an
 * agent is owed. This file is what makes such a change loud.
 */
class PriceMatrixTest {

    @Test
    fun `the farmer ladder is the locked one`() {
        assertEquals(60_000L, KumeaNPack.G50.farmerPriceCents)   // KES 600
        assertEquals(105_000L, KumeaNPack.G100.farmerPriceCents) // KES 1,050
        assertEquals(150_000L, KumeaNPack.G150.farmerPriceCents) // KES 1,500
    }

    @Test
    fun `every price is flat 150 plus 9 per gram`() {
        // The structure is the whole point: cost is per SACHET, not per gram —
        // a 50 g sachet costs the same to fill, seal, label and ship as a 150 g
        // one. Stored rather than computed (policy numbers, rounded to 5 KES),
        // so this is the check that the stored values still express the rule.
        for (pack in KumeaNPack.entries) {
            val expected = (150L + 9L * pack.grams) * 100L
            assertEquals(
                "${pack.label} must be 150 + 9/gram",
                expected,
                pack.farmerPriceCents,
            )
        }
    }

    @Test
    fun `buying small does not pay for a large farmer`() {
        // Requirement ② of the matrix. Under the old flat 10/gram, 3 x 50 g and
        // 1 x 150 g both cost 1,500 and a large farmer could split their order
        // for free. The flat component is what closes that.
        val threeSmall = 3 * KumeaNPack.G50.farmerPriceCents
        val oneLarge = KumeaNPack.G150.farmerPriceCents
        assertEquals(180_000L, threeSmall)
        assertTrue("splitting an order must cost more, not the same", threeSmall > oneLarge)
        assertEquals("a 20% penalty", 20L, (threeSmall - oneLarge) * 100 / oneLarge)

        // And the same principle one size down: 2 x 50 g vs 1 x 100 g.
        val twoSmall = 2 * KumeaNPack.G50.farmerPriceCents
        assertTrue(twoSmall > KumeaNPack.G100.farmerPriceCents)
    }

    @Test
    fun `smallholders can enter at 600, not 1500`() {
        // Requirement ① — the absolute number is what affordability of entry
        // means, not the per-gram rate.
        assertEquals(60_000L, KumeaNPack.catalogue.minOf { it.farmerPriceCents })
    }

    @Test
    fun `every price rounds to the nearest five shillings`() {
        // Kenya's smallest practical coin, and these get spoken aloud.
        for (pack in KumeaNPack.entries) {
            assertEquals(
                "${pack.label} is not a whole 5 KES",
                0L,
                (pack.farmerPriceCents / 100) % 5,
            )
        }
    }

    @Test
    fun `a price is looked up by sku, and the codes read Kumea N`() {
        assertEquals(60_000L, PriceMatrix.farmerPriceCents("KUMEA-N-50G"))
        assertEquals(105_000L, PriceMatrix.farmerPriceCents("KUMEA-N-100G"))
        assertEquals(150_000L, PriceMatrix.farmerPriceCents("KUMEA-N-150G"))

        for (pack in KumeaNPack.entries) {
            assertTrue("pack codes read Kumea N, never BFX-", pack.sku.startsWith("KUMEA-N-"))
        }
    }

    @Test
    fun `an unknown sku fails loudly instead of defaulting`() {
        // A zero would record a free sale against a live commission engine and
        // be indistinguishable from a legitimately discounted one. A fallback
        // price would be a wrong number nobody typed. Both are silent; both are
        // unrecoverable after the fact.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            PriceMatrix.farmerPriceCents("BFX-150G")
        }
        assertTrue(thrown.message!!.contains("Refusing to guess"))

        assertThrows(IllegalArgumentException::class.java) {
            PriceMatrix.farmerPriceCents("")
        }
    }

    @Test
    fun `the catalogue is the three locked sizes, cheapest first`() {
        assertEquals(
            listOf(KumeaNPack.G50, KumeaNPack.G100, KumeaNPack.G150),
            KumeaNPack.catalogue,
        )
        assertEquals(listOf(50, 100, 150), KumeaNPack.catalogue.map { it.grams })
    }
}
