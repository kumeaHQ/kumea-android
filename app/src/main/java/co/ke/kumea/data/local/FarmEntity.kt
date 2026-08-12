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
 *  - [farmerUserId]         who the farm is FOR.       The owner, as the server sees it.
 *  - [registeredByAgentId]  who TYPED IT IN.           Provenance. No commercial meaning.
 *  - [referrerAgentId]      who GETS PAID.             Commission attribution.
 *
 * The commission engine has been live since 26 June and accrues effective 1
 * June. Setting [referrerAgentId] where [registeredByAgentId] was meant makes
 * ~395 KWAP research farmers accrue commission against agents who did nothing —
 * wrong in the ledger, not merely on screen. When an officer registers a
 * farmer, [referrerAgentId] stays null.
 *
 * TWO NAMES, TWO SUBJECTS (v12, KWAP-01 step 4):
 *
 *  - [name]        what the PLACE is called.   "Sigona". Build-1 locked farmer ≠ farm-name.
 *  - [farmerName]  what the PERSON is called.  "Sila Serem". The register's subject.
 *
 * The person sits on the farm rather than on a User because KWAP farmers have
 * no User — creating one was deferred in KWAP-STEP2-DECISIONS.md §2 (unverified
 * phones, the OTP-collision question, ~395 people who will never open the app).
 * With [farmerUserId] unused this season, the farm row IS the farmer record,
 * which is the model KWAP-01 §2 describes anyway. When a real Farmer/User split
 * arrives with commercial, these two become a backfill source.
 *
 * There is no ward column, deliberately: a registration's ward is derived from
 * [registeredByAgentId] → `AgentEntity.ward`, never typed, so a stored copy
 * could only ever disagree with its source.
 *
 * [cropType], [acres] and [useGps] are DEVICE-ONLY — the server's Farm carries
 * none of them (crop and acreage live on the Field; useGps is pure UI state).
 * They are display denorms rebuilt from local input, and a pull leaves them
 * untouched rather than nulling them. Do not add them to [FarmCreateRequest]:
 * `forbidNonWhitelisted` is on server-side, so an unknown key is a 400, and the
 * client retries 400 for ever.
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
    val farmerName: String? = null,
    val farmerPhone: String? = null,
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
