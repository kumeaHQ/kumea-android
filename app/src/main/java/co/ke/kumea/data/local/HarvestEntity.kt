package co.ke.kumea.data.local

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
