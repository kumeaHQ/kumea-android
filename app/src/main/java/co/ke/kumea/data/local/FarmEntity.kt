package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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
