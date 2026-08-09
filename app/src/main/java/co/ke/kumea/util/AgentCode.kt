package co.ke.kumea.util

/**
 * Client-side agent_code minting (P1-T5) — the on-device mirror of the server's
 * `src/agents/agent-code.ts`. The format is `<PREFIX>-<REGION>-<NNN>`
 * (e.g. `VA-NANDI-014`): the role decides the prefix, the region is slugged to
 * an uppercase alphanumeric token, NNN is a zero-padded sequence.
 *
 * WHY ON DEVICE: the close-gate scenario onboards an agent and records a sale in
 * the SAME airplane-mode session. The order needs the selling agent's code
 * BEFORE the server has ever seen the agent, so the code must be minted locally.
 * The server's CreateAgentDto already accepts an optional `agentCode` and adopts
 * it verbatim (generating one only when absent), so a client-minted code
 * round-trips unchanged: the device mints it, sends it on the CREATE push, and
 * the server stores exactly that code.
 *
 * PROVISIONAL-UNTIL-SYNC (known constraint): NNN is allocated from the codes
 * already on THIS device, so two field devices onboarding offline can mint the
 * same NNN and collide on push (the server treats a caller-supplied duplicate as
 * a hard error). With a single Redmi today the collision risk is zero. A
 * device-prefix scheme or server-side allocation is the multi-device fix (later).
 */
object AgentCode {

    /** Role → code prefix. Officers get a prefix for IDENTITY only, never commerce. */
    fun prefixFor(role: String): String = when (role) {
        "village_agent" -> "VA"
        "agro_dealer" -> "AD"
        "extension_officer" -> "EO"
        "cooperative" -> "CO"
        // Defensive: an unknown role still produces a deterministic token rather
        // than crashing. The four roles above are the only ones the app onboards.
        else -> role.uppercase().filter { it.isLetterOrDigit() }.take(2).ifEmpty { "XX" }
    }

    /**
     * Slug a free-text region into the code token: take the part before any
     * dash/em-dash qualifier, drop a trailing "County", uppercase, strip
     * non-alphanumerics. "Nandi County" → "NANDI". Matches regionSlug() server-side.
     */
    fun regionSlug(region: String): String =
        region
            .split('—', '–', '-')[0]
            .replace(Regex("county", RegexOption.IGNORE_CASE), "")
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "NA" }

    /** The code's stable prefix for a (role, region) — the LIKE key for allocation. */
    fun codePrefix(role: String, region: String): String =
        "${prefixFor(role)}-${regionSlug(region)}-"

    /** Build a code. `seq` is 1-based, padded to 3 digits (1 → "001", 14 → "014"). */
    fun format(role: String, region: String, seq: Int): String =
        "${codePrefix(role, region)}${seq.toString().padStart(3, '0')}"

    /**
     * Next sequence for a (role, region) given the codes already on device.
     * Parses the trailing NNN of every code sharing this prefix and returns
     * max + 1 (1 when none exist). Using max — not count — keeps the sequence
     * monotonic across soft-deletes and pulled server rows.
     */
    fun nextSeq(role: String, region: String, existingCodes: List<String>): Int {
        val prefix = codePrefix(role, region)
        val maxSeq = existingCodes
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .maxOrNull() ?: 0
        return maxSeq + 1
    }
}
