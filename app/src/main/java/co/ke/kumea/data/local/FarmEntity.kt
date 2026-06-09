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
    // T4: the Agent who REGISTERED this farmer. SOFT pointer — no Room ForeignKey,
    // because the referrer (often an officer) need not be on this device; the
    // server owns referential integrity. NON-COMMERCIAL: officers are allowed
    // here. referrer = who registered; agent_code = who sold.
    val referrerAgentId: String? = null,
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
