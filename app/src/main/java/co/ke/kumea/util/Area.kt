package co.ke.kumea.util

import kotlin.math.roundToLong

/**
 * THE ONE PLACE A `Double` ACREAGE BECOMES CENTI-ACRES (KWAP-03-V2, VERIFY-5).
 *
 * This project holds three representations of an area, and that is the problem
 * this object exists to contain:
 *
 *  - `farms.acres`  — `Double?`   (v9, the farm's size as the officer typed it)
 *  - `fields.acres` — `String`    (deliberately: it preserves the exact decimal)
 *  - `plantings.plantedAreaCenti` — `Long` (this ticket; centi, like every other
 *    quantity the impact report divides by)
 *
 * Yield is centi-`Long`. If area stayed `Double`, `qtyKgCenti ÷ acres` would mix
 * integer and float in the one division the December impact report turns on —
 * exactly the discipline this project already holds for money and for harvest
 * quantities. So planted area is centi too, and the Double→centi crossing
 * happens HERE, once, with the rounding written down rather than implied.
 *
 * 0.375 acres → 37.5 centi-acres → **38**. It rounds. It is not truncated to 37
 * behind a silent `toLong()`, and the fact that a third decimal place cannot
 * survive is a property of the target type, stated in one place, not a surprise
 * discovered later in a report.
 *
 * ── TWO DECIMAL PLACES IS A DECISION, NOT A SIDE-EFFECT (14 Aug 2026) ────────
 *
 * CLAUDE.md's Units table specifies area as `Decimal(10, 4)` — four decimal
 * places — and `plantings.plantedAreaCenti` deliberately keeps only two. That
 * divergence was reviewed before any planting row existed, which is the only
 * time it is cheap to change, and centi was chosen:
 *
 *  - **The resolution is already far below the measurement error.** 0.01 acre is
 *    about 40 m². Planted area here is a farmer answering "how much of your
 *    shamba did you plant?" from memory, standing in a field. The fourth decimal
 *    place (4 m²) is precision the input cannot carry, and storing it would
 *    imply a confidence nobody has.
 *  - **It has to divide into a centi-Long.** Yield per acre is
 *    `harvests.qtyKgCenti ÷ plantedAreaCenti`. Matching scales keeps that
 *    division in integers end to end; a `Decimal(10, 4)` on one side would put a
 *    scale conversion in the middle of the one calculation the season is judged
 *    on.
 *  - **`Decimal(10, 4)` describes the SERVER's column.** It is a Postgres type
 *    for `farms.acres`, and the device has never stored a Decimal — `farms.acres`
 *    is a `Double?` and `fields.acres` is a `String`. Centi-Long is the device's
 *    existing answer for every quantity that gets summed or divided.
 *
 * If a later season needs sub-centi planted area, it needs a different input
 * question first — a measured area, not a recalled one.
 */
object Area {

    /**
     * `farms.acres` (Double) → centi-acres (Long). The pre-fill crossing, and
     * the only sanctioned one — do not write `(acres * 100).toLong()` anywhere.
     *
     * Negative and non-finite inputs collapse to 0: an acreage cannot be
     * negative, and NaN reaching the impact report as a number would be worse
     * than reaching it as an absence.
     */
    fun fromAcresDouble(acres: Double): Long =
        if (!acres.isFinite() || acres <= 0.0) 0L else (acres * 100).roundToLong()

    /** "1.6" → 160; null on anything unparseable. Delegates to the centi parser. */
    fun parseToCenti(input: String): Long? = Quantity.parseToCenti(input)

    /** 160 → "1.6". Integer math, no float. */
    fun formatCenti(centi: Long): String = Quantity.formatCenti(centi)
}
