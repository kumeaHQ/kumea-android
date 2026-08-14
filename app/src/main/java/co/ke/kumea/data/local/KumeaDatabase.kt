package co.ke.kumea.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 9: FarmEntity gains cropType, acres, useGps columns (farmer UI redesign).
 * Version 10 (Build-2): fields gains plantedAt; new harvests table. FIRST
 * NON-DESTRUCTIVE MIGRATION — real user data exists from v10 onward; every
 * future version bump MUST ship a Migration (destructive fallback removed).
 * Version 11 (KWAP-01 step 1): farms gains farmerUserId + registeredByAgentId,
 * so a farm can belong to someone other than the account that created it.
 * Version 12 (KWAP-01 step 4): farms gains farmerName + farmerPhone — the
 * register's subject, which had no home anywhere in the app; and the notes
 * costCategory 'BIOFIX' rows are rewritten to 'OTHER', a value the server's
 * enum actually accepts. See MIGRATION_11_12; both halves are data-visible.
 * Version 13 (KWAP-03): the farmer page becomes a season rather than a ledger,
 * and the December impact report gets its inputs. farms gains 9 columns —
 * location metadata (the fix that `useGps` only claimed), the stamped ward, and
 * the recalled baseline; fields gains trialRole for the split-plot arm;
 * harvests gains canonical kilograms, without which a yield-per-acre cannot be
 * computed at all; and two new tables, farm_crops (the grouped multi-select,
 * with "interested" as a sales signal) and kumea_n_received (the KWAP-02 shim).
 * See MIGRATION_12_13 — the harvests half rewrites rows, not just shape.
 * Version 14 (KWAP-03-V2): planting stops being a column and becomes a season
 * record. New `plantings` table, farm-level, carrying the four attributes §2.4
 * adds (crop, planted area, seed weight, variety, seed cost) plus trialRole,
 * which moves off `fields`; `fields.plantedAt` is backfilled into it and then
 * retired in place, the same no-drop pattern used for `useGps`. `notes` gains
 * the device-only sourceType/sourceId pair that keeps a seed-cost purchase
 * linked to the planting that wrote it, so "invested" cannot double-count.
 * See MIGRATION_13_14.
 */
@Database(
    entities = [
        AgentEntity::class,
        FarmEntity::class,
        FarmCropEntity::class,
        FieldEntity::class,
        HarvestEntity::class,
        KumeaNReceivedEntity::class,
        NoteEntity::class,
        OrderEntity::class,
        PlantingEntity::class,
        SyncConflictEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class KumeaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun farmDao(): FarmDao
    abstract fun farmCropDao(): FarmCropDao
    abstract fun fieldDao(): FieldDao
    abstract fun harvestDao(): HarvestDao
    abstract fun kumeaNReceivedDao(): KumeaNReceivedDao
    abstract fun noteDao(): NoteDao
    abstract fun orderDao(): OrderDao
    abstract fun plantingDao(): PlantingDao
    abstract fun syncConflictDao(): SyncConflictDao
}
