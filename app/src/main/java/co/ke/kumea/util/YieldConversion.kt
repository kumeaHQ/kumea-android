package co.ke.kumea.util

import co.ke.kumea.data.local.HarvestConversions
import co.ke.kumea.data.local.HarvestUnits

/**
 * Farmer units → canonical kilograms (KWAP-03 §4.4).
 *
 * The single place the conversion happens, because it is the arithmetic the
 * whole season's result rests on: yield-per-acre, the comparison against the
 * recalled baseline, and the treated-vs-control difference are all computed from
 * kilograms, and none of them can be computed at all from "5 bags".
 *
 * Integer math throughout, matching [Quantity] — these numbers are summed across
 * ~395 farms into one headline figure, and float drift in a headline figure is
 * the kind of error nobody catches because it looks like data.
 */
object YieldConversion {

    /**
     * quantity × kg-per-unit, both centi, result centi.
     *
     * 2.5 gorogoro (250) × 2.00 kg (200) / 100 = 500 = 5.00 kg. The divide is
     * last so the intermediate keeps its precision.
     */
    fun toKgCenti(quantityCenti: Long, factorCenti: Long): Long =
        quantityCenti * factorCenti / 100

    /**
     * The kg-per-unit we may assume — or null, which means ASK.
     *
     * Bags return null on purpose. A bag is 50 kg or 90 kg depending on crop and
     * county, so any default is wrong for roughly half the dataset by nearly a
     * factor of two. One extra tap at entry, while the farmer is standing there,
     * is cheaper than an unrecoverable ambiguity in December.
     */
    fun defaultFactorCenti(unit: String): Long? = when (unit) {
        HarvestUnits.KG -> HarvestConversions.KG_CENTI
        HarvestUnits.GOROGORO -> HarvestConversions.GOROGORO_CENTI
        else -> null
    }

    /**
     * Kilograms per acre, centi in / centi out — the yield sanity line (§2.8).
     *
     * 🔴 THE DIVISOR IS PLANTED AREA, NEVER FARM AREA. A farmer who sowed 1.6 of
     * their 3 acres and harvested 720 kg got 450 kg/acre, not 240. Dividing by
     * the shamba's size would understate exactly the farmers who were most
     * cautious about trying Kumea N, which is the population the impact report
     * most needs to read correctly.
     *
     * Integer math, and the multiply comes first so the intermediate keeps its
     * precision: 72000 × 100 / 160 = 45000 = 450.00 kg/acre.
     *
     * Null when there is no planted area to divide by — §2.8 then shows total
     * kilograms only and skips the per-acre line rather than inventing one.
     */
    fun kgPerAcreCenti(qtyKgCenti: Long, plantedAreaCenti: Long): Long? =
        if (plantedAreaCenti <= 0) null else qtyKgCenti * 100 / plantedAreaCenti
}
