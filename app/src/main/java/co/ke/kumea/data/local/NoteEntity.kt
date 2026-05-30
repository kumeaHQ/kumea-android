package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Note belongs to a Field (which belongs to a Farm). Like Farm/Field, the
 * client- and server-side share the same UUID primary key (Ticket 1.3), so
 * there is no separate serverId. Reuses the SyncAction enum from FarmEntity.
 *
 * Ticket 3.2 — first money on device. amountCents is a **Long** (native Room
 * INTEGER), never Double/Float: cents are integers and floats corrupt them
 * above 2^53. This is the money half of the precision discipline that acres
 * (String) established for area. On the wire amountCents travels as a String
 * (see NoteCreateRequest/NoteResponse); the Long↔String conversion happens only
 * at the network boundary inside NoteRepository. Display formatting (KES x.xx)
 * happens only at the very UI edge (see util/Money.kt).
 *
 * amountCents is an unsigned magnitude (>= 0). The `type` carries the sign at
 * rollup time (SALE +, PURCHASE −, ACTIVITY-with-cost −) — signed amounts are
 * never stored. PURCHASE/SALE require an amount; ACTIVITY may omit it.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            // CASCADE never fires during normal operation because soft delete is an
            // UPDATE, not a DELETE (same as Field → Farm).
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fieldId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val fieldId: String,
    val type: NoteType,
    val body: String,
    val amountCents: Long?,
    val occurredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

enum class NoteType {
    ACTIVITY,
    PURCHASE,
    SALE,
}
