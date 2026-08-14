package co.ke.kumea.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VERIFY-5's consequence, pinned.
 *
 * `farms.acres` is a `Double?` and yield is a centi-`Long`, so somewhere a float
 * has to become an integer. This asserts that it happens in exactly one place,
 * rounds rather than truncating, and that the rounding is a stated rule instead
 * of whatever `toLong()` happened to do.
 */
class AreaTest {

    @Test
    fun `whole and one-decimal acreages convert exactly`() {
        assertEquals(300L, Area.fromAcresDouble(3.0))
        assertEquals(160L, Area.fromAcresDouble(1.6))
        assertEquals(50L, Area.fromAcresDouble(0.5))
    }

    @Test
    fun `a third decimal place rounds, it is not truncated`() {
        // 0.375 acres is 37.5 centi-acres. A silent (x * 100).toLong() would
        // give 37 and lose the half quietly; the documented behaviour is 38.
        assertEquals(38L, Area.fromAcresDouble(0.375))
        assertEquals(37L, Area.fromAcresDouble(0.374))
    }

    @Test
    fun `float representation error does not leak into the integer`() {
        // 1.15 is not exactly representable in binary floating point. Rounding
        // absorbs that; truncation would have produced 114.
        assertEquals(115L, Area.fromAcresDouble(1.15))
        assertEquals(29L, Area.fromAcresDouble(0.29))
    }

    @Test
    fun `impossible acreages collapse to zero rather than propagating`() {
        // A negative or non-finite area would reach the impact report as a
        // divisor. Zero is refused by YieldConversion.kgPerAcreCenti, which is
        // the honest outcome; NaN would not be.
        assertEquals(0L, Area.fromAcresDouble(-1.0))
        assertEquals(0L, Area.fromAcresDouble(0.0))
        assertEquals(0L, Area.fromAcresDouble(Double.NaN))
        assertEquals(0L, Area.fromAcresDouble(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `centi acres round-trip through the string form`() {
        assertEquals("1.6", Area.formatCenti(160L))
        assertEquals(160L, Area.parseToCenti("1.6"))
        assertEquals("3", Area.formatCenti(300L))
        assertNull(Area.parseToCenti("not an acreage"))
    }
}
