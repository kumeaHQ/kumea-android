package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Same shape as HarvestDao — the standard offline-first push/pull surface. */
@Dao
interface KumeaNReceivedDao {

    /** Zone 1 of the farm page: what this farmer received, newest first. */
    @Query(
        "SELECT * FROM kumea_n_received WHERE farmId = :farmId " +
            "AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY occurredAt DESC"
    )
    fun getActiveByFarm(farmId: String): Flow<List<KumeaNReceivedEntity>>

    @Query("SELECT * FROM kumea_n_received WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<KumeaNReceivedEntity>

    @Query("SELECT MAX(updatedAt) FROM kumea_n_received")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(records: List<KumeaNReceivedEntity>)

    @Upsert
    suspend fun upsert(record: KumeaNReceivedEntity)

    @Query("UPDATE kumea_n_received SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :id")
    suspend fun markSynced(id: String, serverUpdatedAt: String)

    @Query("UPDATE kumea_n_received SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :id")
    suspend fun markSyncedDelete(id: String, deletedAt: String)
}
