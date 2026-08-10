package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A farm is the farmer. Until v11 that identity was implicit — a farm belonged
 * to whoever held the JWT — which is why an officer tapping "add farmer" would
 * have attached the farm to their own account (KWAP-01 §2).
 *
 * THREE AGENT/USER REFERENCES, THREE DIFFERENT MEANINGS. Do not merge them:
 *
 *  - [farmerUserId]         who the farm is FOR.       null = self-owned (all pre-v11 rows).
 *  - [registeredByAgentId]  who TYPED IT IN.           Provenance. No commercial meaning.
 *  - [referrerAgentId]      who GETS PAID.             Commission attribution.
 *
 * The commission engine has been live since 26 June and accrues effective 1
 * June. Setting [referrerAgentId] where [registeredByAgentId] was meant makes
 * ~395 KWAP research farmers accrue commission against agents who did nothing —
 * wrong in the ledger, not merely on screen. When an officer registers a
 * farmer, [referrerAgentId] stays null.
 */
@Entity(tableName = "farms")
data class FarmEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cropType: String? = null,
    val acres: Double? = null,
    val locationLat: Double?,
    val locationLng: Double?,
    val useGps: Boolean = false,
    val waterSource: String?,
    val referrerAgentId: String? = null,
    val farmerUserId: String? = null,
    val registeredByAgentId: String? = null,
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
