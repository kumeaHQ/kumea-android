package co.ke.kumea.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Guardrail (Ticket 3.2 AC #13): the amount parser uses integer math, never
 * `(input.toDouble() * 100).toLong()`. These tests pin both the happy path and
 * the on-device 2^53 canary that proves the float path would corrupt money.
 */
class MoneyTest {

    @Test
    fun `parses whole shillings to cents`() {
        assertEquals(200000L, Money.parseToCents("2000"))
        assertEquals(0L, Money.parseToCents("0"))
    }

    @Test
    fun `parses decimal shillings to cents (integer math)`() {
        assertEquals(200050L, Money.parseToCents("2000.50"))
        assertEquals(200005L, Money.parseToCents("2000.05"))
        assertEquals(200050L, Money.parseToCents("2000.5"))   // single decimal → .50
        assertEquals(5L, Money.parseToCents("0.05"))
        assertEquals(200000L, Money.parseToCents("2000."))    // trailing dot → .00
    }

    @Test
    fun `2^53 canary - integer parse is exact where the double path is not`() {
        // 9007199254740993 cents = KES 90,071,992,547,409.93 — one cent past 2^53,
        // the smallest integer a Double cannot represent.
        val input = "90071992547409.93"

        val cents = Money.parseToCents(input)
        assertEquals(9007199254740993L, cents)

        // The forbidden path: routing the same input through a Double corrupts it.
        @Suppress("DEPRECATION")
        val viaDouble = (input.toDouble() * 100).toLong()
        assertNotEquals(9007199254740993L, viaDouble)
    }

    @Test
    fun `formats cents as KES with grouping, byte-for-byte above 2^53`() {
        assertEquals("KES 2,000.00", Money.formatCents(200000L))
        assertEquals("KES 2,000.50", Money.formatCents(200050L))
        assertEquals("KES 0.05", Money.formatCents(5L))
        assertEquals("KES 90,071,992,547,409.93", Money.formatCents(9007199254740993L))
    }

    @Test
    fun `formats negative cents for a P and L loss (Ticket 3-3)`() {
        // The ledger's net can be a loss. The fractional part must not go
        // negative — the bug being guarded is "-1,200.-50".
        assertEquals("KES -1,200.00", Money.formatCents(-120000L))
        assertEquals("KES -1,200.50", Money.formatCents(-120050L))
        assertEquals("KES -0.05", Money.formatCents(-5L))
        // A negative total above 2^53 still formats byte-for-byte.
        assertEquals("KES -90,071,992,547,409.93", Money.formatCents(-9007199254740993L))
    }

    @Test
    fun `round-trips parse - format above 2^53`() {
        val cents = Money.parseToCents("90071992547409.93")!!
        assertEquals(9007199254740993L, cents)
        assertEquals("KES 90,071,992,547,409.93", Money.formatCents(cents))
    }

    @Test
    fun `rejects invalid amounts`() {
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("   "))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("-5"))      // no sign — magnitude only
        assertNull(Money.parseToCents("1.234"))   // > 2 decimals
        assertNull(Money.parseToCents("1.2.3"))   // two dots
        assertNull(Money.parseToCents("1 000"))   // internal space
        assertNull(Money.parseToCents("1e3"))     // exponent
        assertNull(Money.parseToCents("1,000"))   // grouping char not allowed on input
    }
}
