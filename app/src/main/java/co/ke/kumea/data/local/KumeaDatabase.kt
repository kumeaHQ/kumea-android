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
 */
@Database(
    entities = [AgentEntity::class, FarmEntity::class, FieldEntity::class, HarvestEntity::class, NoteEntity::class, OrderEntity::class, SyncConflictEntity::class],
    version = 11,
    exportSchema = true,
)
abstract class KumeaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun farmDao(): FarmDao
    abstract fun fieldDao(): FieldDao
    abstract fun harvestDao(): HarvestDao
    abstract fun noteDao(): NoteDao
    abstract fun orderDao(): OrderDao
    abstract fun syncConflictDao(): SyncConflictDao
}
