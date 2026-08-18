package co.ke.kumea.ui.screen.order

import co.ke.kumea.domain.model.KumeaNPack
import co.ke.kumea.domain.model.Persona
import co.ke.kumea.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The money rules of a sale (KWAP-06 §3.1–3.3): price, channel and attribution
 * are all DERIVED, and none of them is a human input any more.
 *
 * ── WHY THIS IS A TEST AND NOT A CODE REVIEW ────────────────────────────────
 *
 * The screen used to take a typed unit price, a chosen channel and a "Sold by"
 * agent picked from every commission-eligible agent on the device. The
 * commission engine is live and accrues backdated to 1 June, and a real agent
 * (VA-NAIROBI-001) is recording real sales — so each of those was a way for one
 * mistap to change who gets paid and how much.
 *
 * Deleting three widgets is easy to undo by accident. These assertions are what
 * says the derivation is the contract.
 */
class SaleAttributionTest {

    private fun seller(role: String, code: String = "VA-NANDI-014") =
        SellerIdentity(agentId = "agent-uuid", agentCode = code, role = role)

    // ── channel and attribution derive from the caller's role ─────────────

    @Test
    fun `a village agent sells on the agent channel, attributed to themselves`() {
        val me = seller(SellerIdentity.ROLE_VILLAGE_AGENT)

        assertEquals("agent", me.channel)
        assertEquals("agent-uuid", me.attributedAgentId)
        assertEquals("VA-NANDI-014", me.attributedAgentCode)
    }

    @Test
    fun `a dealer records the channel and attributes to nobody`() {
        // The dealer's margin is Order-level, not a commission rule (see
        // scripts/seed-commission-rule.ts). Attributing a dealer sale to the
        // dealer would pay a commission that does not exist.
        val dealer = seller(SellerIdentity.ROLE_AGRO_DEALER, code = "AD-KISII-001")

        assertEquals("dealer", dealer.channel)
        assertNull(dealer.attributedAgentId)
        assertNull(dealer.attributedAgentCode)
    }

    @Test
    fun `a role with no defined channel refuses rather than guessing`() {
        // `cooperative` is commission-eligible and maps to the VILLAGE_AGENT
        // persona, but none of the server's five channels describes it. Guessing
        // one would attribute a sale down a path nobody chose, against an engine
        // that pays out.
        val coop = seller("cooperative")

        assertNull("no channel means the save must refuse", coop.channel)
        assertNull(coop.attributedAgentId)
    }

    @Test
    fun `an officer is never the attribution, at this layer too`() {
        // Structurally unreachable — Persona.allowsEarnings keeps an officer off
        // the agent home, and the server rejects officer attribution outright.
        // Asserted anyway: the officer boundary is defended at every layer, never
        // assumed from the layer above.
        val officer = seller(Persona.ROLE_EXTENSION_OFFICER, code = "EO-NANDI-041")

        assertNull(officer.channel)
        assertNull(officer.attributedAgentId)
    }

    @Test
    fun `a blank agent code still attributes, because the UUID is the key`() {
        // P1-T8: the stable UUID is the attribution and it is correct before the
        // server has canonicalised the code. The old picker required a non-blank
        // code because it was rendering a label — a display constraint enforced
        // on money.
        val fresh = seller(SellerIdentity.ROLE_VILLAGE_AGENT, code = "")

        assertEquals("agent-uuid", fresh.attributedAgentId)
        assertNull("nothing to display, but the sale is still attributed", fresh.attributedAgentCode)
    }

    // ── price derives from the pack ───────────────────────────────────────

    @Test
    fun `the recorded unit price is the pack price, for every pack`() {
        // §5 of the ticket, exactly: 150 g → 150000, 100 g → 105000, 50 g → 60000.
        assertEquals(150_000L, state(KumeaNPack.G150).unitPriceCents)
        assertEquals(105_000L, state(KumeaNPack.G100).unitPriceCents)
        assertEquals(60_000L, state(KumeaNPack.G50).unitPriceCents)
    }

    @Test
    fun `the line total is quantity times the derived price`() {
        val three = state(KumeaNPack.G50, qty = "3")

        assertEquals(180_000L, three.lineTotalCents)
        assertEquals(Money.lineTotalCents(3, 60_000L), three.lineTotalCents)
    }

    @Test
    fun `an invalid quantity has no total rather than a wrong one`() {
        assertNull(state(KumeaNPack.G150, qty = "").lineTotalCents)
        assertNull(state(KumeaNPack.G150, qty = "0").lineTotalCents)
        assertNull(state(KumeaNPack.G150, qty = "-2").lineTotalCents)
        assertNull(state(KumeaNPack.G150, qty = "two").lineTotalCents)
    }

    @Test
    fun `even the largest possible quantity stays exact`() {
        // Written first as "a quantity that would overflow Long has no total" —
        // and it failed, which is the useful outcome. Int.MAX_VALUE sachets at
        // KES 1,500 is 3.2e14 cents, four orders of magnitude inside Long. Since
        // qty is an Int, the product CANNOT overflow at any price in the matrix.
        //
        // So the guard in Money.lineTotalCents is unreachable from this screen,
        // and the property worth pinning is the one that is actually true: the
        // total stays exact at the extreme, because the multiplication is Long
        // math. Do this in Int and it wraps negative at ~14,300 sachets of 150 g.
        val absurd = state(KumeaNPack.G150, qty = Int.MAX_VALUE.toString())

        assertEquals(322_122_547_050_000L, absurd.lineTotalCents)
        assertTrue("must never wrap negative", absurd.lineTotalCents!! > 0)
    }

    // ── the form cannot record until it knows who and for whom ────────────

    @Test
    fun `recording is blocked until the seller identity has loaded`() {
        val loading = OrderFormState(selectedFarmerId = "farm-1", identityLoaded = false)
        assertTrue(!loading.canRecord)

        val ready = OrderFormState(
            selectedFarmerId = "farm-1",
            identityLoaded = true,
            seller = seller(SellerIdentity.ROLE_VILLAGE_AGENT),
        )
        assertTrue(ready.canRecord)
    }

    @Test
    fun `an account with no agent record cannot record a sale`() {
        // A sale with no seller has nobody to attribute to. Better to refuse in
        // words than to write a row whose attribution has to be reconstructed.
        val orphan = OrderFormState(
            selectedFarmerId = "farm-1",
            identityLoaded = true,
            seller = null,
        )
        assertTrue(!orphan.canRecord)
    }

    private fun state(pack: KumeaNPack, qty: String = "1") = OrderFormState(pack = pack, qty = qty)
}
