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
 * exactly what `MIGRATION_11_12` adds, with the affinity and nullability the
 * ALTER statements produce. It cannot prove the SQL executes; it does catch the
 * mismatch that has actually bitten this codebase — a column added to an entity
 * and forgotten in the migration.
 *
 * When you add v13, add a case here in the same commit.
 */
class SchemaMigrationTest {

    private val schemaDir: File = listOf(
        File("schemas/co.ke.kumea.data.local.KumeaDatabase"),
        File("app/schemas/co.ke.kumea.data.local.KumeaDatabase"),
    ).firstOrNull { it.isDirectory }
        ?: error("Exported Room schemas not found — exportSchema must stay true")

    private data class Column(val affinity: String, val notNull: Boolean)

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
            )
        }
    }

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
        val tablesOf = { version: Int ->
            Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
                .jsonObject.getValue("database").jsonObject
                .getValue("entities").jsonArray
                .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
                .toSet()
        }
        assertEquals(tablesOf(11), tablesOf(12))

        // The other half of v12 is a data fix (costCategory 'BIOFIX' → 'OTHER'),
        // which changes rows rather than shape — notes' columns must be identical.
        assertEquals(columns(11, "notes"), columns(12, "notes"))
    }
}
