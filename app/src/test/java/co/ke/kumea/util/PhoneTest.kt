package co.ke.kumea.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phone normalisation matters more since KWAP-01 step 4 than it did at sign-in.
 *
 * At sign-in the number is the user's own and an OTP verifies it seconds later.
 * On the officer's register it belongs to someone else, is transcribed from
 * handwriting, and NOTHING will ever verify it — the server's validation is
 * deliberately loose there, because a format 400 would be retried for ever by
 * the sync queue. So this function is the only gate on the whole path.
 */
class PhoneTest {

    @Test
    fun `accepts the forms a WAO actually types`() {
        assertEquals("+254712345678", normalizeKenyanPhone("0712345678"))
        assertEquals("+254712345678", normalizeKenyanPhone("0712 345 678"))
        assertEquals("+254712345678", normalizeKenyanPhone("254712345678"))
        assertEquals("+254712345678", normalizeKenyanPhone("+254 712 345 678"))
        assertEquals("+254712345678", normalizeKenyanPhone("712345678"))
        // Airtel's 01x range, not just Safaricom's 07x.
        assertEquals("+254112345678", normalizeKenyanPhone("0112345678"))
    }

    @Test
    fun `rejects what it cannot make sense of`() {
        assertNull(normalizeKenyanPhone(""))
        assertNull(normalizeKenyanPhone("0712345"))          // too short
        assertNull(normalizeKenyanPhone("07123456789"))      // too long
        assertNull(normalizeKenyanPhone("0812345678"))       // not a mobile prefix
        assertNull(normalizeKenyanPhone("+255712345678"))    // wrong country
        assertNull(normalizeKenyanPhone("not a number"))
    }
}
