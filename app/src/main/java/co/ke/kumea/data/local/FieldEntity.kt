package co.ke.kumea.data.local

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
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)
