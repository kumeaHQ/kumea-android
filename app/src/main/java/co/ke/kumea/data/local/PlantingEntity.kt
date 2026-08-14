package co.ke.kumea.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ONE PLANTING = ONE SEASON'S SOWING ON ONE FARM (KWAP-03-V2 §2.3).
 *
 * ── WHY THIS IS AN ENTITY AND NOT FOUR MORE COLUMNS ─────────────────────────
 *
 * Planting was `fields.plantedAt` — a single nullable column added in v10. This
 * ticket asks it five more questions (crop, planted area, seed weight, variety,
 * seed cost), and there were only two places to put them if planting stayed a
 * column: on `fields`, the entity the farmer's vocabulary is being retired from
 * (§2.2), or on `farms`, which permanently caps a farm at ONE planting ever.
 * Neither survives a second season, and the KWAP research track is explicitly
 * a first season with more expected to follow.
 *
 * ── FARM-LEVEL, NO fieldId ──────────────────────────────────────────────────
 *
 * `farmId`, deliberately, even though §2.2 leaves `FieldEntity` untouched and
 * every farm still has exactly one auto-created Field. Field is disappearing
 * from what the farmer sees; a new table keyed to it would be new debt pointed
 * at the thing being retired. Harvests and notes keep their `fieldId` because
 * re-parenting live migrated rows is a different, more expensive decision that
 * §2.2 explicitly defers.
 *
 * ── CENTI, NOT DOUBLE ───────────────────────────────────────────────────────
 *
 * [plantedAreaCenti] and [seedKgCenti] are hundredths, as Longs. Yield per acre
 * is `harvests.qtyKgCenti ÷ plantedAreaCenti` and both sides must be the same
 * kind of number. See [co.ke.kumea.util.Area] for the single Double→centi
 * crossing from `farms.acres`.
 *
 * ── PLANTED AREA IS NOT FARM AREA ───────────────────────────────────────────
 *
 * The distinction is the entire reason this column exists rather than reading
 * `farms.acres` at report time. A farmer who planted half their shamba and
 * harvested proportionally would otherwise read as having got half the yield
 * per acre — the impact report would understate Kumea N on exactly the farms
 * that were most cautious about trying it.
 *
 * ⚠️ DEVICE-ONLY, AND NOT SYNCED YET. There is no `/plantings` resource on the
 * server — see [co.ke.kumea.data.repository.PlantingRepository] and the
 * commented-out binding in `di/RepositoryModule.kt`.
 */
@Entity(
    tableName = "plantings",
    foreignKeys = [
        ForeignKey(
            entity = FarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["farmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("farmId")],
)
data class PlantingEntity(
    @PrimaryKey val id: String,
    val farmId: String,
    /** UTC date, "YYYY-MM-DD". Capped at today by the picker — never the future. */
    val plantedOn: String,
    /**
     * REQUIRED. A farm may grow several crops and a variety is meaningless
     * without knowing which crop it is a variety of (§2.3).
     */
    val crop: String,
    /** Free text this season (§7.3) — no per-crop variety list exists to pick from. */
    val seedVariety: String? = null,
    /** Hundredths of a kilogram. */
    val seedKgCenti: Long = 0,
    /** Hundredths of an acre. NOT the farm's size — see the header. */
    val plantedAreaCenti: Long = 0,
    /** KES cents. Null when the farmer skipped it; 0 is a stated free, not a skip. */
    val seedCostCents: Long? = null,
    /**
     * [TrialRole]. MOVED HERE from `fields.trialRole` (VERIFY-8 found it had
     * already shipped in v13): a control plot is a property of what was planted
     * this season, not a permanent fact about the land. The Field column is
     * retired in place, same as `fields.plantedAt`.
     */
    @ColumnInfo(defaultValue = TrialRole.NONE)
    val trialRole: String = TrialRole.NONE,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)
