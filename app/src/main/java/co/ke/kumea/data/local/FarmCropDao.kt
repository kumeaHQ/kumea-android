package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * No pendingSync/getPendingSync here, unlike every other DAO: `farm_crops` is
 * not a sync entity. The crop set travels as an array on the farm row (KWAP-03
 * §8), so the Farm's push is what carries it and the Farm's pull is what
 * replaces it — see [replaceForFarm].
 */
@Dao
interface FarmCropDao {

    @Query("SELECT * FROM farm_crops WHERE farmId = :farmId ORDER BY crop ASC")
    fun getByFarm(farmId: String): Flow<List<FarmCropEntity>>

    @Query("SELECT * FROM farm_crops WHERE farmId = :farmId ORDER BY crop ASC")
    suspend fun getByFarmOnce(farmId: String): List<FarmCropEntity>

    /**
     * The primary growing crop, for `farms.cropType`'s display denorm. `MIN` on
     * the key rather than an insertion order, because there is no insertion
     * order in a composite-key table and a stable answer beats an arbitrary one.
     */
    @Query("SELECT crop FROM farm_crops WHERE farmId = :farmId AND status = 'growing' ORDER BY crop ASC LIMIT 1")
    suspend fun primaryGrowingCrop(farmId: String): String?

    @Upsert
    suspend fun upsertAll(crops: List<FarmCropEntity>)

    @Query("DELETE FROM farm_crops WHERE farmId = :farmId")
    suspend fun deleteForFarm(farmId: String)

    /**
     * Replace-on-pull, per farm, in one transaction.
     *
     * Delete-then-insert rather than a diff because the set is the unit of
     * truth: a crop the user unticked has to disappear, and an upsert alone
     * cannot express a removal. `@Transaction` matters — a pull interrupted
     * between the two statements would otherwise leave a farm with no crops at
     * all, which reads as "grows nothing" rather than as a failed sync.
     *
     * CALLERS MUST SKIP FARMS WITH pendingSync = true. That guard lives in
     * FarmRepository.pullSince() alongside the same guard for the farm row
     * itself; without it a pull would drop locally-added `interested` rows that
     * have never reached the server.
     */
    @Transaction
    suspend fun replaceForFarm(farmId: String, crops: List<FarmCropEntity>) {
        deleteForFarm(farmId)
        if (crops.isNotEmpty()) upsertAll(crops)
    }
}
