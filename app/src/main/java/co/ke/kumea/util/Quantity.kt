package co.ke.kumea.util

/**
 * Harvest-quantity parsing/formatting — the Money.kt discipline applied to
 * yield. Quantities live as Long HUNDREDTHS of the unit (120 = 1.2 bags) on
 * device, and as plain decimal strings ("1.2") on the wire, matching the
 * acres-as-string precedent. Integer math only; no Double anywhere.
 */
object Quantity {

    private val DECIMAL_REGEX = Regex("^(\\d+)(?:\\.(\\d{1,2}))?$")

    /** "1.2" -> 120; "3" -> 300; null on anything unparseable or > 2 decimals. */
    fun parseToCenti(input: String): Long? {
        val match = DECIMAL_REGEX.matchEntire(input.trim()) ?: return null
        val whole = match.groupValues[1].toLongOrNull() ?: return null
        val fracRaw = match.groupValues[2]
        val frac = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 10
            else -> fracRaw.toLong()
        }
        // Overflow guard: whole part bounded far above any plausible harvest.
        if (whole > 9_999_999L) return null
        return whole * 100 + frac
    }

    /** 120 -> "1.2"; 300 -> "3"; 305 -> "3.05". Integer math, no float. */
    fun formatCenti(centi: Long): String {
        val whole = centi / 100
        val frac = centi % 100
        return when {
            frac == 0L -> "$whole"
            frac % 10 == 0L -> "$whole.${frac / 10}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }
}
