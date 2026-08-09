package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local audit log for sync conflicts.
 *
 * When the server returns 409 (stale updatedAt), the local pending write is
 * discarded, the server's version replaces it, and this table records what was
 * lost together with what won. No flusher worker in Sprint 0 — inspect via
 * adb shell during development.
 *
 * conflictType enum values:
 *   create_409 — server rejected POST because the record already exists
 *                (should not occur with client UUIDs, but here if it does)
 *   update_409 — server rejected PATCH due to stale updatedAt
 *   // delete_409 — reserved. Per Ticket 1.3, DELETE returns 204 No Content
 *   //   and is idempotent. Deleting an already-deleted record returns 204
 *   //   again, never 409. There is no "delete conflict" case in the API
 *   //   contract because deletes don't take an updatedAt body. This value
 *   //   is commented out as dead code; re-enable if the API ever introduces
 *   //   conditional deletes.
 */
@Entity(tableName = "audit_sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val localPayload: String,
    val serverPayload: String,
    val conflictType: String,
    val occurredAt: String,
)
