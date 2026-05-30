package co.ke.kumea.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Ticket 2.2: FarmEntity replaces PlaceholderEntity.
 * Ticket 3.1: FieldEntity added (version 2 → 3).
 *
 * fallbackToDestructiveMigration() is safe here — no real users yet, and
 * the Sprint 0 schema contains only development/seed data. A proper migration
 * would be pure ceremony until we ship to production users.
 */
@Database(
    entities = [FarmEntity::class, FieldEntity::class, SyncConflictEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class KumeaDatabase : RoomDatabase() {
    abstract fun farmDao(): FarmDao
    abstract fun fieldDao(): FieldDao
    abstract fun syncConflictDao(): SyncConflictDao
}
