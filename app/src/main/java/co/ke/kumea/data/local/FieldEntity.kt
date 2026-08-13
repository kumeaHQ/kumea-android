package co.ke.kumea.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Field belongs to a Farm. Like FarmEntity, the client- and server-side share
 * the same UUID primary key (Ticket 1.3), so there is no separate serverId.
 *
 * acres is a STRING, never Double/Float. It preserves the exact decimal the
 * user entered ("0.3333" stays "0.3333") with no float rounding — the same
 * precision discipline that BigInt cents will use for money in Ticket 3.2.
 * Reuses the SyncAction enum defined alongside FarmEntity.
 */
@Entity(
    tableName = "fields",
    foreignKeys = [
        ForeignKey(
            entity = FarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["farmId"],
            // CASCADE never fires during normal operation because soft delete is an
            // UPDATE, not a DELETE. If a hard delete is added here, child fields will
            // silently vanish.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("farmId")],
)
data class FieldEntity(
    @PrimaryKey val id: String,
    val farmId: String,
    val name: String,
    val acres: String,
    val cropType: String?,
    // Build-2: ISO date the field was planted (server: fields.planted_at).
    // Nullable — unset until the farmer records it. Default keeps existing
    // constructor call sites source-compatible.
    val plantedAt: String? = null,
    /**
     * Split-plot role (KWAP-03 §4.3) — `treated` | `control` | `none`.
     *
     * The entire model cost of the research design. Every farm gets a recalled
     * baseline ([FarmEntity.baselineYieldKgCenti]); a subset — ~2 farms per VBA,
     * ~120 in all — gets a second Field marked `control`, and that arm is the
     * only one that controls for rainfall. A good season otherwise looks exactly
     * like a good product, which is the difference between a result Farm Africa
     * and KEPHIS can use and an anecdote.
     *
     * NOT NULL with a `none` default: a field that was never part of a trial
     * must say so, not read null and leave "was this a control plot?"
     * unanswerable at analysis time. Set at distribution, never retrofittable.
     *
     * The SQL default is declared here rather than only in the migration, so
     * Room's expected schema and the ALTER statement cannot drift apart — a
     * NOT NULL column added without a default cannot be applied to existing
     * rows at all, and one whose default differs from Room's expectation throws
     * on open.
     */
    @ColumnInfo(defaultValue = TrialRole.NONE)
    val trialRole: String = TrialRole.NONE,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

/** Split-plot arms. Strings, not an enum — see [LocationSource] for why. */
object TrialRole {
    /** Got Kumea N. */
    const val TREATED = "treated"

    /** Deliberately did not, on the same shamba, in the same season. */
    const val CONTROL = "control"

    /** Not part of a trial. The default and the common case. */
    const val NONE = "none"
}
