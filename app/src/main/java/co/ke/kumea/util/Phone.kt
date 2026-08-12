package co.ke.kumea.util

/**
 * Normalise a Kenyan mobile number to E.164 (+2547xxxxxxxx / +2541xxxxxxxx).
 * Returns null if the input isn't a valid Safaricom/Airtel-style number.
 *
 * Lives here rather than beside the sign-in screen because it now has two
 * callers with different stakes. At sign-in the number is the user's own and an
 * OTP verifies it moments later, so a rejection costs a retype. On the officer's
 * register the number belongs to someone else, is transcribed from handwriting,
 * and nothing will ever verify it — so this function is the ONLY gate, and it
 * has to be strict here because the server's is deliberately loose (a format
 * 400 would be retried for ever by the sync queue; see CreateFarmDto).
 */
fun normalizeKenyanPhone(input: String): String? {
    val digits = input.trim().filter { it.isDigit() || it == '+' }
    val normalized = when {
        digits.startsWith("+254") -> digits
        digits.startsWith("254") -> "+$digits"
        digits.startsWith("0") -> "+254" + digits.drop(1)
        digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "+254$digits"
        else -> digits
    }
    return if (Regex("^\\+254[17]\\d{8}$").matches(normalized)) normalized else null
}
