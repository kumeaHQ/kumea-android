package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * What a farm grows — the standing profile (KWAP-03 §4.2), finally in a shape
 * that can hold a set.
 *
 * `Crop.kt` has carried the grouped catalogue since 11 Aug with a comment saying
 * the multi-select "needs a column that can hold a set, and `fields.crop_type`
 * is a single string". This is that column. `farms.cropType` is NOT replaced: it
 * stays as the denormalised primary growing crop so the farm-list card keeps
 * reading "Beans · 3.0 acre · Rain" without a join, and — per VERIFY-6 — it is
 * sourced from the Field, not from the Farm wire, which never carried it.
 *
 * TWO STATES, AND THE SECOND ONE IS THE POINT:
 *
 *  - `growing`    — what is on the shamba now. Describes the farm.
 *  - `interested` — what the farmer would grow. A SALES SIGNAL, and nothing else
 *                   in the system records it. A farmer growing maize who is
 *                   interested in soybean is a lead; today that farmer is
 *                   indistinguishable from one who will never plant a legume.
 *
 * MAIZE BELONGS HERE. The bug in the old three-chip row was never that maize was
 * present — it was that a three-item list *implied product need*, so picking
 * maize looked like a request for something Kumea N cannot do (it is a rhizobia
 * inoculant; it does nothing for a cereal). A grouped list that describes the
 * whole farm can hold maize honestly, with legumes simply being the part Kumea
 * serves.
 *
 * NOT A SYNC ENTITY. There is no `pendingSync`/`syncAction` here and there must
 * not be: per KWAP-03 §8 these ride on `FarmResponse` as an array and are
 * replace-on-pull per farm, so the farm row is the single unit of sync and a
 * crop set can never be half-pushed. Replace-on-pull skips farms with local
 * pending writes, so an unsynced `interested` row cannot be wiped by a pull.
 */
@Entity(
    tableName = "farm_crops",
    primaryKeys = ["farmId", "crop"],
    foreignKeys = [
        ForeignKey(
            entity = FarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["farmId"],
            // As everywhere else here, CASCADE never fires in normal operation:
            // deletion is a soft `deletedAt` UPDATE, not a DELETE.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("farmId")],
)
data class FarmCropEntity(
    val farmId: String,
    /** A `Crops` key — lowercase, stable, never localised ("green_gram"). */
    val crop: String,
    /** [CropStatus] value. */
    val status: String,
)

object CropStatus {
    const val GROWING = "growing"
    const val INTERESTED = "interested"
}
