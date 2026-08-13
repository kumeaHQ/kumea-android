package co.ke.kumea.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One harvest record for one field (Build-2). This is the Proof-of-Loop row:
 * quantity + unit as the farmer stated it, the kept/sold split, and replant
 * intent — the 2026 gate metric.
 *
 * quantityCenti/keptCenti/soldCenti are HUNDREDTHS of the unit as a Long
 * (120 = 1.2 bags) — the money discipline applied to yield. NEVER float.
 * unit / replantIntent are server-enum strings verbatim ("bags"|"kg"|"gorogoro",
 * "yes"|"no"|"unknown") — no client-side mapping layer to drift.
 *
 * ── WHY qtyKgCenti EXISTS (v13, KWAP-03 §4.4) ────────────────────────────────
 *
 * `5` + `bags` is not a yield. A bag is 50 kg or 90 kg depending on the crop and
 * who is holding it, and a gorogoro is a tin of roughly 2 kg — so "5 bags from 3
 * acres" cannot be divided into a yield-per-acre, cannot be compared against
 * [FarmEntity.baselineYieldKgCenti], and cannot be summed across farms. Every
 * input the December impact report needs was already being captured except the
 * one that makes them commensurable.
 *
 * The conversion happens ONCE, at entry, while the farmer is standing there and
 * can be asked "what size bag?" — one extra tap. It is not done later in a
 * script, because by then there is nobody left to ask. [conversionFactorCenti]
 * and [conversionSource] are stored alongside so a wrong default can be
 * re-derived: if the 90 kg assumption turns out to be wrong for beans, the rows
 * that used a default are identifiable and fixable, and the rows where the
 * farmer stated the size are left alone.
 *
 * Centi rather than the ticket's `Double`: these get summed across ~395 farms
 * into one headline number, and the two neighbouring quantity columns in this
 * same table are already centi-Long for exactly that reason.
 */
@Entity(
    tableName = "harvests",
    foreignKeys = [
        ForeignKey(
            entity = FieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fieldId")],
)
data class HarvestEntity(
    @PrimaryKey val id: String,
    val fieldId: String,
    val harvestDate: String,
    val quantityCenti: Long,
    val unit: String,
    val keptCenti: Long?,
    val soldCenti: Long?,
    val replantIntent: String,
    val replantMonth: String?,
    /**
     * CANONICAL KILOGRAMS × 100. Every yield calculation reads this and only
     * this. NOT NULL with a 0 default so the migration can backfill existing
     * rows in the same statement — see MIGRATION_12_13, which converts the rows
     * it can and leaves the rest at 0 rather than inventing a bag size.
     */
    @ColumnInfo(defaultValue = "0")
    val qtyKgCenti: Long = 0,
    /** Kilograms per unit × 100 that was actually applied (90 kg → 9000). */
    @ColumnInfo(defaultValue = "0")
    val conversionFactorCenti: Long = 0,
    /** [ConversionSource] — whether a human stated the factor or we assumed it. */
    @ColumnInfo(defaultValue = ConversionSource.UNKNOWN)
    val conversionSource: String = ConversionSource.UNKNOWN,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

/** Client-side constants for the server enum strings. */
object HarvestUnits {
    const val BAGS = "bags"
    const val KG = "kg"
    const val GOROGORO = "gorogoro"
}

object ReplantIntent {
    const val YES = "yes"
    const val NO = "no"
    const val UNKNOWN = "unknown"
}

/**
 * Where [HarvestEntity.conversionFactorCenti] came from. The distinction is the
 * whole point of storing the factor: a default can be wrong for a whole crop and
 * corrected in bulk later, a stated figure cannot and must never be overwritten.
 */
object ConversionSource {
    /** The farmer answered "what size bag?". Authoritative; never rewrite these. */
    const val USER_STATED = "user_stated"

    /** We applied [HarvestConversions]. Re-derivable if a default turns out wrong. */
    const val DEFAULT_TABLE = "default_table"

    /**
     * Pre-v13 row recorded in bags, migrated. The wizard never asked what size,
     * so there is no honest factor to write — 50 vs 90 kg is nearly a doubling
     * and a guess here would silently corrupt the one number the season turns
     * on. Left at 0 and labelled, so these rows are findable and re-askable
     * rather than plausible and wrong.
     */
    const val UNKNOWN = "unknown"
}

/**
 * The default kilograms-per-unit table, centi. Deliberately tiny and explicit —
 * a number that lives in a constant can be found, questioned and changed; the
 * same number inlined in a conversion expression cannot.
 */
object HarvestConversions {
    /** A kg is a kg. Never asked, never defaulted. */
    const val KG_CENTI = 100L

    /**
     * The gorogoro (2 kg tin) is standard enough to default and small enough
     * that being wrong costs little. Stored explicitly on every row anyway.
     */
    const val GOROGORO_CENTI = 200L

    /** The two bag sizes the wizard offers. A bag is never defaulted — it is asked. */
    const val BAG_50KG_CENTI = 5_000L
    const val BAG_90KG_CENTI = 9_000L
}
