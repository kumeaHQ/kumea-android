package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Same shape as [HarvestDao] (the 3.1 discipline) — the parent is Farm rather
 * than Field, which is the only structural difference.
 */
@Dao
interface PlantingDao {
    @Query("SELECT * FROM plantings WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY plantedOn DESC")
    fun getAllActive(): Flow<List<PlantingEntity>>

    /** Active plantings for one farm, most recent sowing first. */
    @Query("SELECT * FROM plantings WHERE farmId = :farmId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY plantedOn DESC")
    fun getActiveByFarm(farmId: String): Flow<List<PlantingEntity>>

    @Query("SELECT * FROM plantings WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): PlantingEntity?

    /**
     * The yield sanity line's read (§2.8): the planted area to divide by. A
     * suspend one-shot rather than a Flow — the harvest wizard asks once, at the
     * moment it needs to show the per-acre figure.
     */
    @Query("SELECT * FROM plantings WHERE farmId = :farmId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY plantedOn DESC LIMIT 1")
    suspend fun getLatestForFarm(farmId: String): PlantingEntity?

    @Query("SELECT * FROM plantings WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<PlantingEntity>

    @Query("SELECT MAX(updatedAt) FROM plantings")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(plantings: List<PlantingEntity>)

    @Upsert
    suspend fun upsert(planting: PlantingEntity)

    @Query("UPDATE plantings SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :plantingId")
    suspend fun markSynced(plantingId: String, serverUpdatedAt: String)

    @Query("UPDATE plantings SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :plantingId")
    suspend fun markSyncedDelete(plantingId: String, deletedAt: String)
}
