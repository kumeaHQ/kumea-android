package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Direct copy of FieldDao's shape (Build-2, same 3.1 discipline). Table-name
 * and getActiveByField are the only differences.
 */
@Dao
interface HarvestDao {
    @Query("SELECT * FROM harvests WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY harvestDate DESC")
    fun getAllActive(): Flow<List<HarvestEntity>>

    /** Active harvests for one field, newest harvest first. */
    @Query("SELECT * FROM harvests WHERE fieldId = :fieldId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY harvestDate DESC")
    fun getActiveByField(fieldId: String): Flow<List<HarvestEntity>>

    @Query("SELECT * FROM harvests WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<HarvestEntity>

    @Query("SELECT MAX(updatedAt) FROM harvests")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(harvests: List<HarvestEntity>)

    @Upsert
    suspend fun upsert(harvest: HarvestEntity)

    @Query("UPDATE harvests SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :harvestId")
    suspend fun markSynced(harvestId: String, serverUpdatedAt: String)

    @Query("UPDATE harvests SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :harvestId")
    suspend fun markSyncedDelete(harvestId: String, deletedAt: String)
}
