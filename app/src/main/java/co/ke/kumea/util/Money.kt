package co.ke.kumea.util

import java.util.Locale

/**
 * Money lives as integer CENTS (Long) on the device — never a Double/Float.
 * This object is the only place a human-typed amount becomes cents and the only
 * place cents become a display string. Keep all float-free.
 *
 * Guardrail (Ticket 3.2 AC #13): parsing splits on the decimal point and uses
 * integer arithmetic. It must NEVER do `(input.toDouble() * 100).toLong()` —
 * that path rounds through IEEE-754 and corrupts values above 2^53 cents
 * (~KES 90 trillion), the exact bug the money contract exists to prevent.
 */
object Money {

    /**
     * Parse a user-entered KES amount into integer cents.
     *
     * Accepts a non-negative number with an optional decimal point and up to two
     * fractional digits: "2000", "2000.5", "2000.50", "0.05". Returns null for
     * anything else (letters, sign, >2 decimals, multiple dots, overflow).
     *
     * Integer math only: cents = whole * 100 + fractional(padded to 2). No floats.
     */
    fun parseToCents(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val parts = trimmed.split('.')
        if (parts.size > 2) return null

        val whole = parts[0]
        val frac = if (parts.size == 2) parts[1] else ""

        // Both halves must be digits-only; reject signs, spaces, exponents, etc.
        if (whole.isNotEmpty() && !whole.all { it.isDigit() }) return null
        if (frac.isNotEmpty() && !frac.all { it.isDigit() }) return null
        if (whole.isEmpty() && frac.isEmpty()) return null
        if (frac.length > 2) return null

        // Pad the fractional part to exactly two digits (cents).
        val centsPart = (frac + "00").substring(0, 2)
        val wholePart = whole.ifEmpty { "0" }

        return try {
            val shillings = wholePart.toLong()
            val cents = centsPart.toLong()
            Math.addExact(Math.multiplyExact(shillings, 100L), cents)
        } catch (e: ArithmeticException) {
            // Overflow beyond Long range — refuse rather than wrap.
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Format integer cents for display, e.g. 200000 → "KES 2,000.00",
     * 9007199254740993 → "KES 90,071,992,547,409.93". Display-edge only.
     */
    fun formatCents(cents: Long): String {
        val shillings = cents / 100
        val remainder = (cents % 100).toInt()
        // Fixed locale: KES groups thousands with "," and uses "." for the
        // decimal. Keep it deterministic rather than at the mercy of device locale.
        return String.format(Locale.US, "KES %,d.%02d", shillings, remainder)
    }
}
