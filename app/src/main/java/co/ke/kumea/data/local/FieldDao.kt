package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Direct copy of FarmDao's shape (Ticket 3.1). The only differences are the
 * table name and an extra getActiveByFarm() — Field belongs to Farm, so listing
 * a single farm's fields is the natural read.
 */
@Dao
interface FieldDao {
    /** All active (non-deleted) fields across farms, newest-first. */
    @Query("SELECT * FROM fields WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY updatedAt DESC")
    fun getAllActive(): Flow<List<FieldEntity>>

    /** Active fields for a single farm, newest-first. */
    @Query("SELECT * FROM fields WHERE farmId = :farmId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY updatedAt DESC")
    fun getActiveByFarm(farmId: String): Flow<List<FieldEntity>>

    /**
     * Rows with pending local changes that need to be pushed to the server.
     *
     * Invariant (same as FarmDao): pull never touches a row that push hasn't
     * reconciled yet. pullSince() skips rows where pendingSync = true so the
     * push gets its turn; on 409 the conflict handler runs (server wins, local
     * discarded, audit logged); on success pendingSync flips to false.
     */
    @Query("SELECT * FROM fields WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<FieldEntity>

    @Query("SELECT MAX(updatedAt) FROM fields")
    suspend fun getLatestUpdatedAt(): String?

    /**
     * Upsert fields from a server pull. Does NOT clear pendingSync — pending
     * rows are skipped by the caller (see getPendingSync invariant), so this
     * only touches already-reconciled rows.
     */
    @Upsert
    suspend fun upsertAll(fields: List<FieldEntity>)

    @Upsert
    suspend fun upsert(field: FieldEntity)

    /**
     * Marks a row as synced after a CREATE or UPDATE push, flipping syncAction
     * to 'UPDATE' so the next edit of an offline-created row is PATCHed, not
     * re-POSTed (the server treats re-POST as idempotent, but this is cleaner).
     */
    @Query("UPDATE fields SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :fieldId")
    suspend fun markSynced(fieldId: String, serverUpdatedAt: String)

    /** Marks a soft-delete row as synced after a DELETE push succeeds. */
    @Query("UPDATE fields SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :fieldId")
    suspend fun markSyncedDelete(fieldId: String, deletedAt: String)
}
