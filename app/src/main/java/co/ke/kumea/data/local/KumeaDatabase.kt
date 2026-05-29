package co.ke.kumea.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Ticket 2.2: FarmEntity replaces PlaceholderEntity.
 *
 * fallbackToDestructiveMigration() is safe here — no real users yet, and
 * the Sprint 0 schema contains only development/seed data. A proper migration
 * would be pure ceremony until we ship to production users.
 */
@Database(
    entities = [FarmEntity::class, SyncConflictEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class KumeaDatabase : RoomDatabase() {
    abstract fun farmDao(): FarmDao
    abstract fun syncConflictDao(): SyncConflictDao
}
