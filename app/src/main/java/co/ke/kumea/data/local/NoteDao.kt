package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Direct copy of FieldDao's shape (Ticket 3.2). The only differences are the
 * table name and that a Note's "parent" read is by Field (getActiveByField) or
 * by Farm (getActiveByFarm, joining through fields) — Notes are listed per
 * field/farm in the UI. Notes are ordered by occurredAt (the farmer-entered
 * date), newest-first, because a note is a temporal log entry.
 */
@Dao
interface NoteDao {
    /** All active (non-deleted) notes, newest activity first. */
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY occurredAt DESC")
    fun getAllActive(): Flow<List<NoteEntity>>

    /** Active notes for a single field. */
    @Query("SELECT * FROM notes WHERE fieldId = :fieldId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY occurredAt DESC")
    fun getActiveByField(fieldId: String): Flow<List<NoteEntity>>

    /** Active notes across all of a farm's fields (joined through fields). */
    @Query(
        """
        SELECT n.* FROM notes n
        INNER JOIN fields f ON n.fieldId = f.id
        WHERE f.farmId = :farmId
          AND n.deletedAt IS NULL AND n.syncAction != 'DELETE'
        ORDER BY n.occurredAt DESC
        """
    )
    fun getActiveByFarm(farmId: String): Flow<List<NoteEntity>>

    /**
     * Rows with pending local changes that need pushing. Same invariant as
     * FieldDao: pullSince() skips pending rows so push gets its turn first.
     */
    @Query("SELECT * FROM notes WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<NoteEntity>

    @Query("SELECT MAX(updatedAt) FROM notes")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Upsert
    suspend fun upsert(note: NoteEntity)

    /** Marks a row synced after a CREATE/UPDATE push (flips CREATE → UPDATE). */
    @Query("UPDATE notes SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :noteId")
    suspend fun markSynced(noteId: String, serverUpdatedAt: String)

    /** Marks a soft-delete row synced after a DELETE push succeeds. */
    @Query("UPDATE notes SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :noteId")
    suspend fun markSyncedDelete(noteId: String, deletedAt: String)
}
