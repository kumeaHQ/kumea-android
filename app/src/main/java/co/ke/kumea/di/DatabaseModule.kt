package co.ke.kumea.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.ke.kumea.data.local.AgentDao
import co.ke.kumea.data.local.FarmCropDao
import co.ke.kumea.data.local.FarmDao
import co.ke.kumea.data.local.FieldDao
import co.ke.kumea.data.local.HarvestDao
import co.ke.kumea.data.local.KumeaDatabase
import co.ke.kumea.data.local.KumeaNReceivedDao
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.OrderDao
import co.ke.kumea.data.local.PlantingDao
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

    /**
     * KWAP-03 (v12 → v13). The largest migration in the project so far, and the
     * one with the most at stake: it carries the columns the December impact
     * report is computed from, none of which can be retrofitted afterwards.
     *
     * Written against the exported `12.json` and validated against Room's
     * generated `13.json` — the DDL below is the `createSql` from that file with
     * the pre-existing columns removed, not hand-guessed. Column ORDER differs
     * (ALTER appends, CREATE TABLE interleaves) and that is fine: Room compares
     * columns by name, which is why the v11 and v12 ALTERs work too.
     *
     * ── farms × 9 ───────────────────────────────────────────────────────────
     * All nullable TEXT/REAL/INTEGER with no default, so every existing row
     * reads null — honest, because nobody captured an accuracy, a ward or a
     * baseline for those farms and a zero would claim they did.
     *
     * `county` is NOT here. KWAP-03 §4.1 asked for it, but neither `agents` nor
     * the server's Agent holds a county to stamp from, and deriving it from
     * `agents.region` would fill a column called county from a field the
     * canonical regions doc defines as one of seven regions. Dropped 13 Aug —
     * see FarmEntity's header.
     *
     * ── fields × 1 ──────────────────────────────────────────────────────────
     * `trialRole` is NOT NULL DEFAULT 'none'. A NOT NULL column cannot be added
     * to a populated table without a default, and the default must match Room's
     * expectation exactly, which is why the entity declares it via @ColumnInfo
     * rather than only here.
     *
     * ── harvests × 3, AND A ROW REWRITE ─────────────────────────────────────
     * The three columns are cheap; the UPDATE is the point. Rows already
     * recorded in `kg` convert exactly, so they get their canonical kilograms
     * and a factor of 1.00 with nothing assumed. Rows in `gorogoro` take the
     * standard 2 kg tin, marked `default_table` so that assumption is findable
     * and reversible if it turns out to be regional.
     *
     * Rows in `bags` are DELIBERATELY LEFT AT ZERO. A bag is 50 kg or 90 kg
     * depending on crop and county — nearly a doubling — and the wizard never
     * asked, so there is no number here that is better than an admission of not
     * knowing. Zero with `conversionSource = 'unknown'` makes exactly those rows
     * greppable and re-askable; a plausible guess would be indistinguishable
     * from data and would quietly bias the one figure the season is judged on.
     *
     * ── two new tables ──────────────────────────────────────────────────────
     * `farm_crops` (grouped multi-select, with `interested` as the sales signal)
     * and `kumea_n_received` (the KWAP-02 shim), each with the FK index Room
     * expects. Both DDL blocks are `13.json`'s verbatim, `${'$'}{TABLE_NAME}`
     * substituted.
     */
    internal val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── farms: location metadata (§4.1) ──────────────────────────────
            // Four columns because a coordinate alone cannot be judged: how
            // precise, how old, from which provider, and whether a human ever
            // stood there and said yes.
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `locationAccuracyM` REAL")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `locationSource` TEXT")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `locationCapturedAt` TEXT")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `locationConfirmedAt` TEXT")

            // ── farms: the stamped ward (§4.1) ───────────────────────────────
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `ward` TEXT")

            // ── farms: the recalled baseline (§4.1, decision 1) ──────────────
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `baselineYieldCenti` INTEGER")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `baselineYieldUnit` TEXT")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `baselineYieldKgCenti` INTEGER")
            db.execSQL("ALTER TABLE `farms` ADD COLUMN `baselineCrop` TEXT")

            // ── fields: the split-plot arm (§4.3) ────────────────────────────
            db.execSQL("ALTER TABLE `fields` ADD COLUMN `trialRole` TEXT NOT NULL DEFAULT 'none'")

            // ── harvests: canonical kilograms (§4.4) ─────────────────────────
            db.execSQL("ALTER TABLE `harvests` ADD COLUMN `qtyKgCenti` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `harvests` ADD COLUMN `conversionFactorCenti` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `harvests` ADD COLUMN `conversionSource` TEXT NOT NULL DEFAULT 'unknown'")

            // Already in kilograms: exact, nothing assumed.
            db.execSQL(
                """
                UPDATE `harvests` SET
                    `qtyKgCenti` = `quantityCenti`,
                    `conversionFactorCenti` = 100,
                    `conversionSource` = 'user_stated'
                WHERE `unit` = 'kg'
                """.trimIndent()
            )
            // The 2 kg tin. quantityCenti × factorCenti / 100 keeps the whole
            // conversion in integers — 2.5 gorogoro (250) × 200 / 100 = 500 = 5.00 kg.
            db.execSQL(
                """
                UPDATE `harvests` SET
                    `qtyKgCenti` = `quantityCenti` * 200 / 100,
                    `conversionFactorCenti` = 200,
                    `conversionSource` = 'default_table'
                WHERE `unit` = 'gorogoro'
                """.trimIndent()
            )
            // `bags` is intentionally not converted. See the header.

            // ── farm_crops (§4.2) ────────────────────────────────────────────
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `farm_crops` (
                    `farmId` TEXT NOT NULL,
                    `crop` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    PRIMARY KEY(`farmId`, `crop`),
                    FOREIGN KEY(`farmId`) REFERENCES `farms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_farm_crops_farmId` ON `farm_crops` (`farmId`)")

            // ── kumea_n_received (§7) ────────────────────────────────────────
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `kumea_n_received` (
                    `id` TEXT NOT NULL,
                    `farmId` TEXT NOT NULL,
                    `strainCode` TEXT NOT NULL,
                    `packSizeG` INTEGER NOT NULL,
                    `batchNumber` TEXT NOT NULL,
                    `qty` INTEGER NOT NULL,
                    `occurredAt` TEXT NOT NULL,
                    `recordedByAgentId` TEXT NOT NULL,
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    `deletedAt` TEXT,
                    `pendingSync` INTEGER NOT NULL,
                    `syncAction` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`farmId`) REFERENCES `farms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_kumea_n_received_farmId` ON `kumea_n_received` (`farmId`)")
        }
    }

    /**
     * KWAP-03-V2 (v13 → v14). Planting becomes a season record.
     *
     * Written against the exported `13.json` and validated against Room's
     * generated `14.json`; `SchemaMigrationTest` diffs the pair so a forgotten
     * column fails at JVM speed rather than on a handset.
     *
     * ── plantings ───────────────────────────────────────────────────────────
     * New table, farm-level. DDL is `14.json`'s `createSql` verbatim with
     * `${'$'}{TABLE_NAME}` substituted, plus the FK index Room expects.
     *
     * ── notes × 2 ───────────────────────────────────────────────────────────
     * `sourceType` / `sourceId`, nullable TEXT, no default — every existing note
     * reads null, which is correct: none of them was generated by a planting.
     * Device-only; see NoteEntity's header for why they are not on the wire.
     *
     * ── THE BACKFILL, AND WHY IT IS WRITTEN AS THOUGH IT MATTERS ────────────
     * Every `fields.plantedAt` becomes a planting row on that field's farm.
     *
     * On the two handsets checked it moves ZERO rows — the only field on the
     * test device has a null `plantedAt`, and VERIFY-2 confirmed it. That is not
     * a reason to skip it or to write it loosely. The KWAP roster is ~395 farms
     * that do not exist on any device yet, the officer flow has shipped, and a
     * WAO who records a planting date next week and then takes this update would
     * otherwise watch that date vanish with no error anywhere.
     *
     * What the backfilled row can and cannot say:
     *  - `plantedOn` — the date, truncated to its first 10 characters. v10 wrote
     *    a full ISO instant here; `plantings.plantedOn` is a date.
     *  - `crop` — from `fields.cropType`, falling back to `farms.cropType`, and
     *    to '' only if both are absent. NOT 'unknown': that string would be
     *    indistinguishable from a farmer who really grows a crop called unknown,
     *    and an empty crop is greppable.
     *  - `seedKgCenti` / `plantedAreaCenti` — **0, deliberately**. Nobody was
     *    ever asked. Pre-filling planted area from `farms.acres` would look
     *    helpful and would be a fabricated denominator in the yield-per-acre
     *    figure the whole impact report turns on — the same reasoning that left
     *    v13's `bags` harvests at zero rather than guessing 50 or 90 kg.
     *  - `trialRole` — carried across from `fields.trialRole`, the one v13 value
     *    that IS real and would otherwise be stranded on a retired column.
     *
     * The row is written `pendingSync = 0` / `syncAction = 'UPDATE'`: there is no
     * `/plantings` route on the server, so marking it pending would queue a push
     * at an endpoint that 404s — and 404 is not terminal in these repositories.
     *
     * ── RETIRED IN PLACE ────────────────────────────────────────────────────
     * `fields.plantedAt` and `fields.trialRole` are NOT dropped. Dropping a
     * column in SQLite means recreating the table, which on a populated `fields`
     * with two FK children is the riskiest thing this migration could do for no
     * user-visible gain. They stop being written and stop being read; the entity
     * keeps them so Room's expected schema still matches.
     */
    internal val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── plantings (§2.3) ─────────────────────────────────────────────
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `plantings` (
                    `id` TEXT NOT NULL,
                    `farmId` TEXT NOT NULL,
                    `plantedOn` TEXT NOT NULL,
                    `crop` TEXT NOT NULL,
                    `seedVariety` TEXT,
                    `seedKgCenti` INTEGER NOT NULL,
                    `plantedAreaCenti` INTEGER NOT NULL,
                    `seedCostCents` INTEGER,
                    `trialRole` TEXT NOT NULL DEFAULT 'none',
                    `createdAt` TEXT NOT NULL,
                    `updatedAt` TEXT NOT NULL,
                    `deletedAt` TEXT,
                    `pendingSync` INTEGER NOT NULL,
                    `syncAction` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`farmId`) REFERENCES `farms`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_plantings_farmId` ON `plantings` (`farmId`)")

            // ── notes: the linked-record pair (§2.5) ─────────────────────────
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `sourceType` TEXT")
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `sourceId` TEXT")

            // ── backfill fields.plantedAt → plantings ────────────────────────
            // A deterministic id from the field's own id, so re-running this
            // migration cannot produce a second planting for the same field.
            db.execSQL(
                """
                INSERT OR IGNORE INTO `plantings` (
                    `id`, `farmId`, `plantedOn`, `crop`, `seedVariety`,
                    `seedKgCenti`, `plantedAreaCenti`, `seedCostCents`,
                    `trialRole`, `createdAt`, `updatedAt`, `deletedAt`,
                    `pendingSync`, `syncAction`
                )
                SELECT
                    'planting-' || f.`id`,
                    f.`farmId`,
                    substr(f.`plantedAt`, 1, 10),
                    COALESCE(NULLIF(f.`cropType`, ''), NULLIF(fa.`cropType`, ''), ''),
                    NULL,
                    0,
                    0,
                    NULL,
                    f.`trialRole`,
                    f.`createdAt`,
                    f.`updatedAt`,
                    NULL,
                    0,
                    'UPDATE'
                FROM `fields` f
                JOIN `farms` fa ON fa.`id` = f.`farmId`
                WHERE f.`plantedAt` IS NOT NULL
                  AND f.`plantedAt` != ''
                  AND f.`deletedAt` IS NULL
                """.trimIndent()
            )
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
        .addMigrations(
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
        )
        .build()

    @Provides
    @Singleton
    fun provideAgentDao(database: KumeaDatabase): AgentDao = database.agentDao()

    @Provides
    @Singleton
    fun provideFarmDao(database: KumeaDatabase): FarmDao = database.farmDao()

    @Provides
    @Singleton
    fun provideFarmCropDao(database: KumeaDatabase): FarmCropDao = database.farmCropDao()

    @Provides
    @Singleton
    fun provideFieldDao(database: KumeaDatabase): FieldDao = database.fieldDao()

    @Provides
    @Singleton
    fun provideHarvestDao(database: KumeaDatabase): HarvestDao = database.harvestDao()

    @Provides
    @Singleton
    fun provideKumeaNReceivedDao(database: KumeaDatabase): KumeaNReceivedDao =
        database.kumeaNReceivedDao()

    @Provides
    @Singleton
    fun provideNoteDao(database: KumeaDatabase): NoteDao = database.noteDao()

    @Provides
    @Singleton
    fun provideOrderDao(database: KumeaDatabase): OrderDao = database.orderDao()

    @Provides
    @Singleton
    fun providePlantingDao(database: KumeaDatabase): PlantingDao = database.plantingDao()

    @Provides
    @Singleton
    fun provideSyncConflictDao(database: KumeaDatabase): SyncConflictDao = database.syncConflictDao()

    private const val DATABASE_NAME = "kumea.db"
}
