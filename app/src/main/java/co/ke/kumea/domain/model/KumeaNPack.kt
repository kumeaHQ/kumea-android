package co.ke.kumea.domain.model

/**
 * THE KUMEA N CATALOGUE, AND THE ONE PLACE A PRICE COMES FROM (KWAP-06 §2).
 *
 * Source: `PRICE-MATRIX-LOCKED.md`, revised 12 Aug 2026, which supersedes the
 * flat-per-gram version of 11 Aug and the 14 Jun `BFX-` codes this replaces.
 *
 * ── WHY PRICE IS DERIVED AND NEVER TYPED ────────────────────────────────────
 *
 * Until this shipped, `OrderCreateScreen` had a free-text "Unit price (KES)"
 * field. The commission engine is LIVE and accrues backdated to 1 June, and a
 * real agent (VA-NAIROBI-001) is recording real sales — so every hand-typed
 * price was commission liability computed from a number a human could fat-finger.
 * A price is policy, not an observation: the farmer pays what the matrix says.
 *
 * ── THE SHAPE OF THE PRICING, WHICH IS THE POINT ────────────────────────────
 *
 * Cost is **per sachet, not per gram** — a 50 g sachet costs the same to fill,
 * seal, label and ship as a 150 g one. So every price is a **flat component plus
 * a per-gram component**, mirroring the cost structure. The farmer ladder is
 * `150 + 9/gram`:
 *
 *   50 g  → 150 + 450  = 600
 *   100 g → 150 + 900  = 1,050
 *   150 g → 150 + 1,350 = 1,500   ← the anchor, unchanged
 *
 * That one change is what makes a small pack affordable to enter on (600, not
 * 1,500) while stopping a large farmer from buying three small packs to game the
 * per-gram rate (1,800 vs 1,500 — a 20% penalty). Flat per-gram pricing did
 * neither.
 *
 * **Prices are stored, not computed from the formula.** They are policy numbers
 * rounded to the nearest 5 KES (a shop's smallest practical coin), so deriving
 * them arithmetically at runtime would reintroduce rounding drift on a number
 * that was decided, not calculated. The formula is here to explain them.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT MODEL ───────────────────────────────────
 *
 * **Strain.** `PRICE-MATRIX-LOCKED.md` says the catalogue is *strain × pack
 * size*, because dosage differs per strain and each has its own packaging. Price
 * does not vary by strain — it is flat + per-gram, and nothing else — so the
 * money rule is complete without it. Adding a strain axis here would mean
 * inventing strain codes for `orders.sku`, and the strain list is exactly what
 * `kumea_n_received.strainCode` currently records as free text. That is
 * catalogue work (KWAP-02), and it can land without touching a price.
 *
 * **The agent commission ladder and the dealer buy prices.** Both are in the
 * matrix and neither belongs on the device: commission is the server's to
 * compute (`commission.accrual.ts`), and showing an agent's own margin on a
 * screen a farmer can see over their shoulder is the leak §1.4 forbids. The
 * device sends the FARMER price and the attribution; the server derives what is
 * owed.
 */
enum class KumeaNPack(
    /** `orders.sku`. Free TEXT server-side with no CHECK — historical rows keep `BFX-`. */
    val sku: String,
    /** Grams per sachet. The pricing dimension. */
    val grams: Int,
    /** What a WAO reads in the picker. */
    val label: String,
) {
    /**
     * The acquisition pack. A farmer with a third of an acre buys a third of an
     * acre's worth — 600 is the entry price that makes smallholder adoption
     * possible at all.
     */
    G50("KUMEA-N-50G", 50, "50 g"),

    /**
     * ⚠️ ADDED 18 AUG, AND ITS PURPOSE IS STILL OPEN. At 150 g ≈ 1 acre, 100 g is
     * two-thirds of an acre — not a unit a farmer thinks in. The likely answer is
     * **forage**: lucerne and desmodium run 50 g ≈ 0.5 kg seed, so 100 g = 1 kg.
     * `PRICE-MATRIX-LOCKED.md` lists this as unsettled and says to settle it
     * before labels are printed. The PRICE is locked either way, which is why
     * the pack can ship now; the dosage line is what is waiting.
     */
    G100("KUMEA-N-100G", 100, "100 g"),

    /** The anchor. ~1 acre, and the one price that has never moved. */
    G150("KUMEA-N-150G", 150, "150 g");

    /**
     * What the FARMER pays, in integer cents. Never a Double — the money
     * discipline, same as [co.ke.kumea.util.Money] and `NoteEntity.amountCents`.
     *
     * The farmer always sees these. Discounts happen upstream, invisibly.
     */
    val farmerPriceCents: Long
        get() = when (this) {
            G50 -> 60_000L    // KES 600
            G100 -> 105_000L  // KES 1,050
            G150 -> 150_000L  // KES 1,500
        }

    companion object {
        /** Picker order: cheapest entry pack first, the way a WAO offers them. */
        val catalogue: List<KumeaNPack> = listOf(G50, G100, G150)
    }
}

/**
 * The single sanctioned price lookup. There are no price literals anywhere else
 * in the app, and there must never be a typed one again.
 */
object PriceMatrix {

    /**
     * The farmer price for a pack, in cents.
     *
     * FAILS LOUDLY on an unknown SKU rather than defaulting to 0 or to anything
     * else. A zero would record a free sale against a live commission engine and
     * look exactly like a legitimately discounted one; a fallback price would be
     * a wrong number nobody typed. Neither is recoverable after the fact, and
     * both are silent. An unknown SKU here means the catalogue changed without
     * this file, which is a bug to see immediately.
     *
     * Historical `BFX-` rows are not looked up — they carry the price they were
     * recorded with. This resolves what a NEW sale costs.
     */
    fun farmerPriceCents(sku: String): Long = packFor(sku).farmerPriceCents

    /** The catalogue entry for a SKU, or a loud failure. See [farmerPriceCents]. */
    fun packFor(sku: String): KumeaNPack =
        KumeaNPack.entries.firstOrNull { it.sku == sku }
            ?: throw IllegalArgumentException(
                "No price for SKU '$sku'. The catalogue is ${KumeaNPack.entries.joinToString { it.sku }}. " +
                    "Refusing to guess — a defaulted price is commission liability nobody typed.",
            )
}
