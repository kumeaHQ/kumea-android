package co.ke.kumea.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Ticket 2.2: FarmEntity replaces PlaceholderEntity.
 * Ticket 3.1: FieldEntity added (version 2 → 3).
 * Ticket 3.2: NoteEntity added (version 3 → 4).
 * Ticket 2.1: NoteEntity.costCategory column added (version 4 → 5).
 * Phase 1a · T5-slice: AgentEntity added; FarmEntity.referrerAgentId column
 *   added (version 5 → 6).
 * P1-T3: OrderEntity added (version 6 → 7).
 * P1-T8: OrderEntity.agentId column added — attribution by stable Agent UUID
 *   (version 7 → 8).
 *
 * fallbackToDestructiveMigration() is safe here — no real users yet, and
 * the Sprint 0 schema contains only development/seed data. A proper migration
 * would be pure ceremony until we ship to production users (the dev device
 * recreates fresh data carrying agentId directly — see P1-T8).
 */
@Database(
    entities = [AgentEntity::class, FarmEntity::class, FieldEntity::class, NoteEntity::class, OrderEntity::class, SyncConflictEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class KumeaDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun farmDao(): FarmDao
    abstract fun fieldDao(): FieldDao
    abstract fun noteDao(): NoteDao
    abstract fun orderDao(): OrderDao
    abstract fun syncConflictDao(): SyncConflictDao
}
