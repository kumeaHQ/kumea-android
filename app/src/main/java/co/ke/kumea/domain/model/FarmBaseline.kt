package co.ke.kumea.domain.model

import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.util.Quantity
import co.ke.kumea.util.YieldConversion

/**
 * "What did you harvest here last season?" (KWAP-03 §4.1, decision 1).
 *
 * THE COUNTERFACTUAL, and the cheapest half of it. Every farm gets this recalled
 * figure; a subset of ~120 also gets a split-plot control field, which is the
 * only arm that controls for rainfall. Recall is confounded — a good season
 * looks exactly like a good product — but it is universal, it costs one screen,
 * and without it the December report has nothing to compare a harvest against.
 *
 * NEITHER HALF CAN BE RETROFITTED. Asking in December what someone harvested
 * fourteen months earlier, after a season of being told a product would help,
 * produces a number shaped by the answer we want. That is why an optional,
 * skippable question ships with registration this month rather than later.
 *
 * [kgCenti] is derived here rather than at the call site so the stated figure
 * and the canonical one can never disagree.
 */
data class FarmBaseline(
    /** As stated, hundredths of [unit]. */
    val quantityCenti: Long,
    /** `bags` | `kg` | `gorogoro` — the harvest wizard's vocabulary. */
    val unit: String,
    /** kg-per-unit actually applied, centi. Asked for bags, defaulted otherwise. */
    val conversionFactorCenti: Long,
    /** What was grown — a `Crops` key. Null when the farmer did not say. */
    val crop: String?,
) {
    val kgCenti: Long get() = YieldConversion.toKgCenti(quantityCenti, conversionFactorCenti)
}

/**
 * The half-typed version of [FarmBaseline], as it exists while someone is
 * filling the form. Shared by both registration flows so the question is asked
 * identically in each — a baseline that means one thing on the farmer's screen
 * and another on the officer's is worse than no baseline.
 */
data class BaselineInput(
    val qty: String = "",
    val unit: String? = null,
    /** kg-per-bag × 100, once asked. Bags are never defaulted. */
    val bagSizeCenti: Long? = null,
    val crop: String? = null,
) {
    val needsBagSize: Boolean get() = unit == HarvestUnits.BAGS && bagSizeCenti == null

    val isStarted: Boolean get() = qty.isNotBlank() || unit != null

    /**
     * The finished baseline, or null — and null is fine. The question is
     * prompted but skippable and must never block a registration: a farmer who
     * cannot remember last season is still a farmer to register.
     */
    fun toBaseline(fallbackCrop: String? = null): FarmBaseline? {
        val unit = unit ?: return null
        val quantityCenti = Quantity.parseToCenti(qty)?.takeIf { it > 0 } ?: return null
        val factor = when (unit) {
            // No default for bags, ever. 50 vs 90 kg is nearly a doubling of the
            // one figure the season is judged on, so a half-answered bag entry
            // yields nothing rather than something plausible.
            HarvestUnits.BAGS -> bagSizeCenti ?: return null
            else -> YieldConversion.defaultFactorCenti(unit) ?: return null
        }
        return FarmBaseline(
            quantityCenti = quantityCenti,
            unit = unit,
            conversionFactorCenti = factor,
            crop = crop ?: fallbackCrop,
        )
    }
}
