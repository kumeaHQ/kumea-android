package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(conflict: SyncConflictEntity)

    /**
     * How many times this row has already been 404'd — the 404 retry budget's
     * counter (see [co.ke.kumea.data.sync.RetryPolicy]). Kept in the audit table
     * rather than in a new column, because the attempt IS a rejection worth
     * recording and a schema change across six tables is not worth a counter.
     */
    @Query("SELECT COUNT(*) FROM audit_sync_conflicts WHERE entityId = :entityId AND conflictType LIKE '%_404'")
    suspend fun count404(entityId: String): Int

    /**
     * THE NEEDS-ATTENTION QUEUE. Rows the server refused in a way no retry will
     * fix — a wire-contract bug, a missing route, a rejected permission. Their
     * payloads are here verbatim, so nothing is lost while the contract is
     * fixed; they just stopped costing a request every sync cycle.
     */
    @Query("SELECT * FROM audit_sync_conflicts WHERE conflictType LIKE '%_terminal_%' ORDER BY occurredAt DESC")
    fun getTerminalRejections(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM audit_sync_conflicts WHERE conflictType LIKE '%_terminal_%'")
    suspend fun countTerminalRejections(): Int
}
