package co.ke.kumea.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the project's hardest constraint from the JVM.
 *
 * Destructive fallback is permanently gone, so a version bump without a matching
 * `Migration` is not a build error — it is a crash on open, on a farmer's phone,
 * the first time the app starts after an update. And DDL that is *nearly* right
 * (wrong affinity, wrong nullability) fails the same way, because Room compares
 * the migrated database against the schema it expects.
 *
 * A real Room `MigrationTestHelper` run needs an instrumented device and this
 * project has no `androidTest` source set, so this is the cheap half: it reads
 * the exported schemas and asserts that what changed between two versions is
 * exactly what the migration for that version adds, with the affinity,
 * nullability and defaults the ALTER statements produce. It cannot prove the SQL
 * executes; it does catch the mismatch that has actually bitten this codebase —
 * a column added to an entity and forgotten in the migration.
 *
 * When you add v15, add a case here in the same commit.
 */
class SchemaMigrationTest {

    private val schemaDir: File = listOf(
        File("schemas/co.ke.kumea.data.local.KumeaDatabase"),
        File("app/schemas/co.ke.kumea.data.local.KumeaDatabase"),
    ).firstOrNull { it.isDirectory }
        ?: error("Exported Room schemas not found — exportSchema must stay true")

    private data class Column(
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String? = null,
    )

    private fun columns(version: Int, table: String): Map<String, Column> {
        val root = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
        val entity = root.jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray
            .single { it.jsonObject.getValue("tableName").jsonPrimitive.content == table }
        return entity.jsonObject.getValue("fields").jsonArray.associate { field ->
            val f = field.jsonObject
            f.getValue("fieldPath").jsonPrimitive.content to Column(
                affinity = f.getValue("affinity").jsonPrimitive.content,
                notNull = f.getValue("notNull").jsonPrimitive.content.toBoolean(),
                defaultValue = f["defaultValue"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun tables(version: Int): Set<String> =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject
            .getValue("entities").jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()

    @Test
    fun `v12 adds exactly the two farm columns MIGRATION_11_12 writes`() {
        val before = columns(11, "farms")
        val after = columns(12, "farms")

        assertEquals(
            "MIGRATION_11_12 adds farmerName and farmerPhone and nothing else",
            setOf("farmerName", "farmerPhone"),
            after.keys - before.keys,
        )
        assertTrue("v12 must not drop a farms column", (before.keys - after.keys).isEmpty())

        // `ALTER TABLE farms ADD COLUMN x TEXT` yields exactly this. A NOT NULL
        // column here would need a DEFAULT in the DDL and would fail on open
        // without one — the loud failure the discipline is designed to produce.
        for (name in listOf("farmerName", "farmerPhone")) {
            assertEquals("$name affinity", "TEXT", after.getValue(name).affinity)
            assertEquals("$name nullability", false, after.getValue(name).notNull)
        }
    }

    @Test
    fun `v12 changes no other table`() {
        assertEquals(tables(11), tables(12))

        // The other half of v12 is a data fix (costCategory 'BIOFIX' → 'OTHER'),
        // which changes rows rather than shape — notes' columns must be identical.
        assertEquals(columns(11, "notes"), columns(12, "notes"))
    }

    @Test
    fun `v13 adds exactly the nine farm columns MIGRATION_12_13 writes`() {
        val added = columns(13, "farms").keys - columns(12, "farms").keys

        assertEquals(
            "MIGRATION_12_13 adds the location metadata, the stamped ward and the baseline",
            setOf(
                "locationAccuracyM", "locationSource", "locationCapturedAt", "locationConfirmedAt",
                "ward",
                "baselineYieldCenti", "baselineYieldUnit", "baselineYieldKgCenti", "baselineCrop",
            ),
            added,
        )

        // NINE, NOT TEN. KWAP-03 §4.1 also specified `county`, and it is absent
        // on purpose: nothing on either side of the wire holds a county to stamp
        // from (`AgentEntity` has region + ward), and filling a column called
        // county from `region` would mean something different from what it says.
        // If a county column ever appears here, it needs a real source first.
        assertTrue("county has no source to derive from — see FarmEntity", "county" !in added)
    }

    @Test
    fun `every v13 farm column is nullable, so existing rows stay honest`() {
        val after = columns(13, "farms")
        val added = after.keys - columns(12, "farms").keys

        // A NOT NULL column here would need a default, and a default would
        // assert a fact about farms registered before the question existed:
        // an accuracy nobody measured, a ward nobody stamped, a baseline nobody
        // was asked for. Null is the honest value and the only correct one.
        for (name in added) {
            assertEquals("$name must be nullable", false, after.getValue(name).notNull)
            assertEquals("$name must carry no default", null, after.getValue(name).defaultValue)
        }
        assertEquals("locationAccuracyM is a Float", "REAL", after.getValue("locationAccuracyM").affinity)
        assertEquals("baselineYieldKgCenti is centi-Long", "INTEGER", after.getValue("baselineYieldKgCenti").affinity)
    }

    @Test
    fun `v13 adds trialRole to fields with the default the ALTER needs`() {
        val after = columns(13, "fields")
        assertEquals(
            setOf("trialRole"),
            after.keys - columns(12, "fields").keys,
        )

        // NOT NULL is the point — a field that was never in a trial must say
        // 'none' rather than read null, or "was this a control plot?" becomes
        // unanswerable at analysis time. NOT NULL then FORCES a default, since
        // ALTER TABLE cannot add a non-null column to a populated table without
        // one, and Room throws on open if that default is not exactly this.
        assertEquals(true, after.getValue("trialRole").notNull)
        assertEquals("'none'", after.getValue("trialRole").defaultValue)
    }

    @Test
    fun `v13 adds the three canonical-kilogram columns to harvests`() {
        val after = columns(13, "harvests")
        assertEquals(
            setOf("qtyKgCenti", "conversionFactorCenti", "conversionSource"),
            after.keys - columns(12, "harvests").keys,
        )

        assertEquals("INTEGER", after.getValue("qtyKgCenti").affinity)
        assertEquals("0", after.getValue("qtyKgCenti").defaultValue)
        assertEquals("INTEGER", after.getValue("conversionFactorCenti").affinity)
        assertEquals("0", after.getValue("conversionFactorCenti").defaultValue)

        // The default is 'unknown', not a bag size. Pre-v13 rows recorded in
        // bags were never asked what size, and 50 vs 90 kg is nearly a doubling
        // of the number the whole season is judged on — so those rows stay at 0
        // and say so, rather than becoming plausible and wrong.
        assertEquals("'unknown'", after.getValue("conversionSource").defaultValue)
    }

    @Test
    fun `v13 adds farm_crops and kumea_n_received and drops nothing`() {
        assertEquals(setOf("farm_crops", "kumea_n_received"), tables(13) - tables(12))
        assertTrue("v13 must not drop a table", (tables(12) - tables(13)).isEmpty())

        // farm_crops is NOT a sync entity: the crop set rides on the farm row as
        // an array and is replace-on-pull per farm (KWAP-03 §8), so the farm is
        // the single unit of sync and a crop set can never be half-pushed.
        // Growing sync columns here would quietly undo that.
        val farmCrops = columns(13, "farm_crops")
        assertEquals(setOf("farmId", "crop", "status"), farmCrops.keys)

        // kumea_n_received is the opposite: a full push/pull entity. What it
        // must never grow is a commercial field — no agentId, no referrerAgentId,
        // no price. The commission engine is live and backdated to 1 June, and
        // these are ~395 farmers who were GIVEN the product.
        val received = columns(13, "kumea_n_received")
        assertTrue("recordedByAgentId is provenance and must be present", "recordedByAgentId" in received)
        assertEquals(
            "a received-record always knows who handed it over",
            true,
            received.getValue("recordedByAgentId").notNull,
        )
        for (commercial in listOf("agentId", "referrerAgentId", "unitPrice", "price", "amountCents")) {
            assertTrue("kumea_n_received must never carry $commercial", commercial !in received)
        }
    }

    @Test
    fun `v13 changes no table it was not meant to`() {
        for (table in listOf("agents", "notes", "orders", "audit_sync_conflicts")) {
            assertEquals("v13 must not touch $table", columns(12, table), columns(13, table))
        }
    }

    @Test
    fun `v14 adds plantings and drops nothing`() {
        assertEquals(setOf("plantings"), tables(14) - tables(13))
        assertTrue("v14 must not drop a table", (tables(13) - tables(14)).isEmpty())

        val plantings = columns(14, "plantings")
        assertEquals(
            setOf(
                "id", "farmId", "plantedOn", "crop", "seedVariety",
                "seedKgCenti", "plantedAreaCenti", "seedCostCents", "trialRole",
                "createdAt", "updatedAt", "deletedAt", "pendingSync", "syncAction",
            ),
            plantings.keys,
        )

        // FARM-LEVEL. A fieldId here would key the new table to the entity §2.2
        // is retiring from the farmer's vocabulary.
        assertTrue("plantings is farm-level, never field-level", "fieldId" !in plantings)
        assertEquals(true, plantings.getValue("farmId").notNull)

        // Required: a variety is meaningless without knowing which crop it is a
        // variety of, and a farm may grow several (§2.3).
        assertEquals("crop is required", true, plantings.getValue("crop").notNull)

        // CENTI-LONG, NOT DOUBLE. Yield per acre is qtyKgCenti ÷
        // plantedAreaCenti; a REAL affinity on either side reintroduces float
        // into the one division the impact report turns on. VERIFY-5 found
        // farms.acres is a Double, which is exactly why this column exists.
        assertEquals("INTEGER", plantings.getValue("plantedAreaCenti").affinity)
        assertEquals("INTEGER", plantings.getValue("seedKgCenti").affinity)
        assertEquals("INTEGER", plantings.getValue("seedCostCents").affinity)

        // Seed cost is nullable — skipping the question and stating zero are
        // different answers, and §2.5 only writes a Purchase note for the former.
        assertEquals("a skipped seed cost is null, not 0", false, plantings.getValue("seedCostCents").notNull)

        // Same NOT NULL + DEFAULT reasoning as v13's fields.trialRole.
        assertEquals(true, plantings.getValue("trialRole").notNull)
        assertEquals("'none'", plantings.getValue("trialRole").defaultValue)
    }

    @Test
    fun `v14 adds exactly the two source columns to notes`() {
        val before = columns(13, "notes")
        val after = columns(14, "notes")

        assertEquals(setOf("sourceType", "sourceId"), after.keys - before.keys)
        assertTrue("v14 must not drop a notes column", (before.keys - after.keys).isEmpty())

        // Nullable, no default: every note that already exists was typed by a
        // human, not generated by a planting, and null says exactly that.
        for (name in listOf("sourceType", "sourceId")) {
            assertEquals("$name affinity", "TEXT", after.getValue(name).affinity)
            assertEquals("$name must be nullable", false, after.getValue(name).notNull)
            assertEquals("$name must carry no default", null, after.getValue(name).defaultValue)
        }
    }

    @Test
    fun `v14 retires fields columns in place rather than dropping them`() {
        val fields = columns(14, "fields")

        // plantedAt and trialRole are no longer written or read — planting is an
        // entity now — but dropping a column in SQLite means recreating the
        // table, and `fields` is populated with two FK children pointing at it.
        // The retirement is behavioural; the columns stay so Room's expected
        // schema still matches what is on the handset.
        assertTrue("fields.plantedAt is retired in place, not dropped", "plantedAt" in fields)
        assertTrue("fields.trialRole is retired in place, not dropped", "trialRole" in fields)
        assertEquals("v14 must not reshape fields at all", columns(13, "fields"), fields)
    }

    @Test
    fun `v14 changes no table it was not meant to`() {
        for (table in listOf("agents", "farms", "harvests", "orders", "farm_crops", "kumea_n_received", "audit_sync_conflicts")) {
            assertEquals("v14 must not touch $table", columns(13, table), columns(14, table))
        }
    }
}
