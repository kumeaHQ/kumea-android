package co.ke.kumea.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.ke.kumea.data.local.AgentDao
import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.HarvestDao
import co.ke.kumea.data.local.KumeaDatabase
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.SyncConflictDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Build-2 (v9 → v10): fields gains plantedAt; new harvests table.
     *
     * DDL must match Room's expected schema for the v10 entities exactly
     * (column affinity + nullability), or Room throws on open — which is the
     * desired failure mode: loud, immediate, at the gate, never silent data loss.
     */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `fields` ADD COLUMN `plantedAt` TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `harvests` (
                    `id` TEXT NOT NULL,
                    `fieldId` TEXT NOT NULL,
                    `harvestDate` TEXT NOT NULL,
                    `quantityCenti` INTEGER NOT NULL,
                    `unit` TEXT NOT NULL,
                    `keptCenti` INTEGER,
                    `soldCenti` INTEGER,
                    `replantIntent` TEXT NOT NULL,
                    `replantMonth` TEXT,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    `deletedAt` TEXT,
                    `pendingSync` INTEGER NOT NULL,
                    `syncAction` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`fieldId`) REFERENCES `fields`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_harvests_fieldId` ON `harvests` (`fieldId`)")
        }
    }

    /**
     * KWAP-01 step 1 (v10 → v11): farms gains farmerUserId + registeredByAgentId.
     *
     * Both nullable with no default, so every existing row reads null — which is
     * exactly the pre-v11 meaning: self-owned, registered by nobody on anyone's
     * behalf. Additive only; no row is rewritten, no data is touched.
     *
     * Written against the exported `10.json` farms table, not inferred:
     * TEXT affinity, nullable, no index, no FK — matching `referrerAgentId`,
     * whose agent id is likewise unconstrained on-device because the agent
     * roster syncs separately and may arrive after the farm.
     */
    // `internal`, not `private`: KumeaDatabaseMigrationTest exercises this exact
    // object. A migration test that re-declares the DDL tests a copy, and the
    // copy is not what ships.
    internal val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `farmerUserId` TEXT")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `registeredByAgentId` TEXT")
        }
    }

    /**
     * KWAP-01 step 4 (v11 → v12). Two unrelated changes ride together because
     * they ship in one release; both are spelled out because both touch data.
     *
     * ── farms.farmerName / farmerPhone ──────────────────────────────────────
     * The register's subject. Nullable TEXT with no default, matching the
     * server's additive `20260812120000_kwap01_step4_farmer_identity`, so every
     * existing row reads null — which is honest: nobody recorded a person
     * against those farms. Written against the exported `11.json` farms table.
     *
     * ── notes.costCategory 'BIOFIX' → 'OTHER' ───────────────────────────────
     * NOT cosmetic, and not optional. `BIOFIX` was client-first and the server's
     * CostCategory enum never gained it, so every note carrying it was rejected
     * with a validation 400 on push and retried for ever (400 is not terminal
     * client-side). Those rows are still sitting pending on any device that
     * recorded a Kumea N purchase.
     *
     * Removing the enum constant without this UPDATE would be worse than the
     * bug: Room stores an enum as its name, so a row reading 'BIOFIX' would
     * throw on deserialisation and take the whole notes query down with it.
     * Rewriting to 'OTHER' unsticks the queue and loses only a label — the
     * amount, the note body and the field are all untouched, and the row pushes
     * on the next cycle. When RB ships a real server value, a later migration
     * can reclassify; the money was never wrong.
     */
    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `farmerName` TEXT")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `farmerPhone` TEXT")
            db.execSQL("UPDATE `notes` SET `costCategory` = 'OTHER' WHERE `costCategory` = 'BIOFIX'")
        }
    }

    @Provides
    @Singleton
    fun provideKumeaDatabase(
        @ApplicationContext context: Context,
    ): KumeaDatabase = Room.databaseBuilder(
        context,
        KumeaDatabase::class.java,
        DATABASE_NAME,
    )
        // DESTRUCTIVE FALLBACK REMOVED (Build-2 T0). From v10 on, every schema
        // change ships a written Migration; a missing one now crashes on open
        // instead of wiping the farm. (The three devices in use today are test
        // handsets — corrected 11 Aug — but this is what protects the first real
        // farmer a WAO registers, which is what step 4 makes possible.)
        .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
        .build()

    @Provides
    @Singleton
    fun provideAgentDao(database: KumeaDatabase): AgentDao = database.agentDao()

    @Provides
    @Singleton
    fun provideFarmDao(database: KumeaDatabase): FarmDao = database.farmDao()

    @Provides
    @Singleton
    fun provideFieldDao(database: KumeaDatabase): FieldDao = database.fieldDao()

    @Provides
    @Singleton
    fun provideHarvestDao(database: KumeaDatabase): HarvestDao = database.harvestDao()

    @Provides
    @Singleton
    fun provideNoteDao(database: KumeaDatabase): NoteDao = database.noteDao()

    @Provides
    @Singleton
    fun provideOrderDao(database: KumeaDatabase): OrderDao = database.orderDao()

    @Provides
    @Singleton
    fun provideSyncConflictDao(database: KumeaDatabase): SyncConflictDao = database.syncConflictDao()

    private const val DATABASE_NAME = "kumea.db"
}
