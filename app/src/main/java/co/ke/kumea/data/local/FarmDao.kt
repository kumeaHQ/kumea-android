package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FarmDao {
    /**
     * Returns all active (non-deleted) farms ordered newest-first.
     *
     * Belt-and-braces: a soft-deleted row should already have deletedAt set,
     * but we filter on syncAction too in case of partial state during a transition
     * (e.g., pendingSync=true, syncAction=DELETE, deletedAt not yet set).
     */
    @Query("SELECT * FROM farms WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY updatedAt DESC")
    fun getAllActive(): Flow<List<FarmEntity>>

    /** All farm IDs currently in Room (for field FK guard). */
    @Query("SELECT id FROM farms WHERE deletedAt IS NULL AND syncAction != 'DELETE'")
    suspend fun getAllIds(): List<String>

    /**
     * Rows with pending local changes that need to be pushed to the server.
     * Ordered oldest-first to make conflict debugging predictable.
     *
     * Invariant: we never let pull touch a row that push hasn't reconciled yet.
     * pullSince() skips rows where pendingSync = true to guarantee the push
     * gets its turn. If the push fails with 409, the conflict handler does the
     * right thing — server wins, local discarded, audit logged. If the push
     * succeeds, pendingSync flips to false and the next pull will see it normally.
     */
    @Query("SELECT * FROM farms WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<FarmEntity>

    @Query("SELECT MAX(updatedAt) FROM farms")
    suspend fun getLatestUpdatedAt(): String?

    /**
     * Upsert all farms from a server pull.
     * Note: this does NOT clear pendingSync — that's intentional.
     * pull rows that are also pending should be skipped (see getPendingSync invariant),
     * so this upsert only touches rows that are already reconciled.
     */
    @Upsert
    suspend fun upsertAll(farms: List<FarmEntity>)

    @Upsert
    suspend fun upsert(farm: FarmEntity)

    /**
     * Marks a row as successfully synced after a CREATE or UPDATE push.
     *
     * After a CREATE succeeds, the next edit-then-sync cycle must PATCH (not POST).
     * Flipping syncAction to 'UPDATE' here ensures that subsequent edits of a row that
     * was created offline will be sent as PATCH requests, not re-POSTed.
     * Without this, the next push would POST again — the server treats it as idempotent
     * per 1.3 so no data corruption, but it's noisier than it should be.
     */
    @Query("UPDATE farms SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :farmId")
    suspend fun markSynced(farmId: String, serverUpdatedAt: String)

    /**
     * Marks a soft-delete row as synced.
     * After a DELETE push succeeds, pendingSync is cleared and deletedAt records
     * the server's deletion timestamp.
     */
    @Query("UPDATE farms SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :farmId")
    suspend fun markSyncedDelete(farmId: String, deletedAt: String)
}
