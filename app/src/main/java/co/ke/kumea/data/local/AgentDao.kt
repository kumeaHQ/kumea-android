package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Agent DAO — a mechanical copy of FarmDao (Phase 1a · T5-slice). Same
 * pendingSync / latest-updatedAt / markSynced contract the SyncableRepository
 * relies on. getAllIds() exists so any entity that references an agent can run
 * an FK guard against the locally-present set (Agent before anything that
 * references it).
 */
@Dao
interface AgentDao {
    @Query("SELECT * FROM agents WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY updatedAt DESC")
    fun getAllActive(): Flow<List<AgentEntity>>

    /** Active agents of a given role (e.g. officers, for an endorsement picker). */
    @Query("SELECT * FROM agents WHERE role = :role AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY agentCode ASC")
    fun getActiveByRole(role: String): Flow<List<AgentEntity>>

    /** All agent IDs currently in Room — the FK-guard set for referencing entities. */
    @Query("SELECT id FROM agents WHERE deletedAt IS NULL AND syncAction != 'DELETE'")
    suspend fun getAllIds(): List<String>

    /**
     * Agent codes already allocated on this device that share a (role, region)
     * prefix — the input to client-side NNN allocation (P1-T5). Includes pending
     * and soft-deleted rows on purpose: a code, once minted, is never reused so
     * the sequence stays monotonic. `prefix` is e.g. "VA-NANDI-".
     */
    @Query("SELECT agentCode FROM agents WHERE agentCode LIKE :prefix || '%'")
    suspend fun getCodesWithPrefix(prefix: String): List<String>

    @Query("SELECT * FROM agents WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<AgentEntity>

    @Query("SELECT MAX(updatedAt) FROM agents")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(agents: List<AgentEntity>)

    @Upsert
    suspend fun upsert(agent: AgentEntity)

    /**
     * Marks a row synced after a CREATE/UPDATE push. Flips syncAction to UPDATE
     * so the next edit PATCHes rather than re-POSTs (same reasoning as FarmDao).
     */
    @Query("UPDATE agents SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :agentId")
    suspend fun markSynced(agentId: String, serverUpdatedAt: String)

    @Query("UPDATE agents SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :agentId")
    suspend fun markSyncedDelete(agentId: String, deletedAt: String)
}
