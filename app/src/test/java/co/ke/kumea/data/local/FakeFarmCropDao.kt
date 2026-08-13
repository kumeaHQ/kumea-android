package co.ke.kumea.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory `farm_crops`, shared by the repository tests.
 *
 * `replaceForFarm` is a `@Transaction` default method on the DAO interface, so
 * overriding delete/upsert is enough — the real replace-on-pull sequencing is
 * exercised rather than reimplemented, which is the point of not stubbing it.
 */
class FakeFarmCropDao : FarmCropDao {
    val rows = mutableListOf<FarmCropEntity>()

    override fun getByFarm(farmId: String): Flow<List<FarmCropEntity>> =
        flowOf(rows.filter { it.farmId == farmId }.sortedBy { it.crop })

    override suspend fun getByFarmOnce(farmId: String): List<FarmCropEntity> =
        rows.filter { it.farmId == farmId }.sortedBy { it.crop }

    override suspend fun primaryGrowingCrop(farmId: String): String? =
        rows.filter { it.farmId == farmId && it.status == CropStatus.GROWING }
            .minByOrNull { it.crop }?.crop

    override suspend fun upsertAll(crops: List<FarmCropEntity>) {
        for (crop in crops) {
            rows.removeAll { it.farmId == crop.farmId && it.crop == crop.crop }
            rows.add(crop)
        }
    }

    override suspend fun deleteForFarm(farmId: String) {
        rows.removeAll { it.farmId == farmId }
    }
}
