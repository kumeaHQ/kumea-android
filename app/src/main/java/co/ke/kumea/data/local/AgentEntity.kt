package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Distribution-layer Agent (Phase 1a · T5-slice). A direct copy of the
 * FarmEntity offline-first shape: client- and server-side share the same UUID
 * primary key (Ticket 1.3), pendingSync + syncAction drive the SyncableRepository
 * push/pull, and soft delete is an UPDATE that sets deletedAt.
 *
 * THE OFFICER ALLOW-LIST, EXPRESSED IN THE TYPE SYSTEM: there is deliberately
 * NO commissionRuleId field here. The device cannot even represent commission on
 * an agent, so an officer can never be given one from the app. Commission is
 * server-side T6 work and is structurally barred for officers there too.
 *
 * endorsedById is a SOFT self-reference (no Room ForeignKey): the endorsing
 * officer frequently is NOT on this device, so a hard FK would wrongly drop a
 * legitimately-pulled agent. The server is the referential-integrity authority.
 * role/status are the server's lowercase enum values kept verbatim as Strings.
 */
@Entity(
    tableName = "agents",
    indices = [Index("role"), Index("region")],
)
data class AgentEntity(
    @PrimaryKey val id: String,
    val role: String,
    val agentCode: String,
    val region: String,
    val ward: String?,
    val linkedContactId: String?,
    val linkedUserId: String?,
    val endorsedById: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)
