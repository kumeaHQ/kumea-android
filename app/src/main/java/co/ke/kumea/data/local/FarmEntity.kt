package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Server- and client-side share the same UUID primary key.
 * Per Ticket 1.3, the NestJS server accepts client-generated UUIDs as its primary key,
 * so there is no separate serverId field — id IS the server ID.
 */
@Entity(tableName = "farms")
data class FarmEntity(
    @PrimaryKey val id: String,
    val name: String,
    val locationLat: Double?,
    val locationLng: Double?,
    val waterSource: String?,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

enum class SyncAction {
    CREATE,
    UPDATE,
    DELETE,
}
