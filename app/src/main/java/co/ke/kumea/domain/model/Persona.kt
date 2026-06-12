package co.ke.kumea.domain.model

/**
 * The signed-in user's persona (P1-T7) — derived ONLY from their linked
 * Agent.role, never chosen by hand. One app, three experiences:
 *
 *   • [FARMER]            — no linked Agent. Own P&L (unchanged from before T7).
 *   • [VILLAGE_AGENT]     — a commission-eligible seller. Sales + (gated) earnings.
 *   • [EXTENSION_OFFICER] — endorsement + ward outcomes. ZERO commercial surface.
 *
 * Roles other than extension_officer that can sell (agro_dealer, cooperative)
 * also map to [VILLAGE_AGENT]: they share the same sales+earnings surface, and
 * the server's /commission/me returns a real (zero) surface for any
 * commission-eligible role. The label is the village_agent UI; the routing fact
 * that matters is officer-vs-seller-vs-farmer.
 *
 * THE OFFICER EXCLUSION lives in [allowsEarnings] — a pure predicate mirroring
 * the server's isCommissionEligible (role != extension_officer). It is the single
 * source of truth the home dispatcher reads to decide whether the earnings
 * component is even instantiated; for an officer it is structurally absent from
 * the view hierarchy, not hidden by a flag.
 */
enum class Persona {
    FARMER,
    VILLAGE_AGENT,
    EXTENSION_OFFICER;

    /**
     * Whether this persona may ever see an earnings/commission surface. False for
     * FARMER (no commission construct) and, critically, EXTENSION_OFFICER (the
     * officer allow-list — zero commercial surface). The earnings composable is
     * only ever reachable when this is true.
     */
    val allowsEarnings: Boolean
        get() = this == VILLAGE_AGENT

    companion object {
        /** The server's extension_officer role string (lowercase, verbatim). */
        const val ROLE_EXTENSION_OFFICER = "extension_officer"

        /**
         * Classify a linked Agent's role string into a persona. A null role means
         * no linked Agent → [FARMER]. An extension_officer maps to
         * [EXTENSION_OFFICER]; every other (commission-eligible) role maps to
         * [VILLAGE_AGENT]. Unknown/blank roles fall back to [VILLAGE_AGENT] only
         * when a linked agent exists — a non-farmer we can't classify still must
         * not be handed an officer's endorsement powers, and the worst case is a
         * (correctly zero) earnings surface, never a commercial leak.
         */
        fun fromAgentRole(role: String?): Persona = when {
            role.isNullOrBlank() -> FARMER
            role == ROLE_EXTENSION_OFFICER -> EXTENSION_OFFICER
            else -> VILLAGE_AGENT
        }
    }
}
