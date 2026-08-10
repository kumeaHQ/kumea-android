package co.ke.kumea.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.ke.kumea.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first instrumented test in this project.
 *
 * `runMigrationsAndValidate` proves the resulting schema has the *shape* Room
 * expects. That is not the guarantee that matters here. Destructive fallback was
 * permanently removed because real farmer records live on real phones, so the
 * guarantee that matters is that **the rows are still there afterwards, with
 * their old values intact** — which is what this test asserts.
 *
 * MIGRATION_9_10 shipped against that same real data untested. This is the
 * migration to start the habit on.
 */
@RunWith(AndroidJUnit4::class)
class KumeaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KumeaDatabase::class.java,
    )

    @Test
    fun migrate10To11_preservesExistingFarmRows_andDefaultsNewColumnsToNull() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO farms (
                    id, name, cropType, acres, locationLat, locationLng, useGps,
                    waterSource, referrerAgentId, createdAt, updatedAt, deletedAt,
                    pendingSync, syncAction
                ) VALUES (
                    '$FARM_ID', 'Mulu Home Farm', 'maize', 2.5, -0.1789, 35.1056, 1,
                    'borehole', '$REFERRER_ID', '2026-06-01T08:30:00Z', '2026-08-09T14:05:00Z', NULL,
                    0, 'UPDATE'
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            /* validateDroppedTables = */ true,
            DatabaseModule.MIGRATION_10_11,
        )

        db.query("SELECT * FROM farms WHERE id = ?", arrayOf(FARM_ID)).use { cursor ->
            assertTrue("the farm row did not survive the migration", cursor.moveToFirst())
            assertEquals(1, cursor.count)

            // Every pre-existing column, unchanged.
            assertEquals("Mulu Home Farm", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("maize", cursor.getString(cursor.getColumnIndexOrThrow("cropType")))
            assertEquals(2.5, cursor.getDouble(cursor.getColumnIndexOrThrow("acres")), 0.0001)
            assertEquals(-0.1789, cursor.getDouble(cursor.getColumnIndexOrThrow("locationLat")), 0.0001)
            assertEquals(35.1056, cursor.getDouble(cursor.getColumnIndexOrThrow("locationLng")), 0.0001)
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("useGps")))
            assertEquals("borehole", cursor.getString(cursor.getColumnIndexOrThrow("waterSource")))
            assertEquals("2026-06-01T08:30:00Z", cursor.getString(cursor.getColumnIndexOrThrow("createdAt")))
            assertEquals("2026-08-09T14:05:00Z", cursor.getString(cursor.getColumnIndexOrThrow("updatedAt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("deletedAt")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("pendingSync")))
            assertEquals("UPDATE", cursor.getString(cursor.getColumnIndexOrThrow("syncAction")))

            // Commission attribution is untouched. registeredByAgentId must never
            // be conflated with it, and the migration must not move it either.
            assertEquals(
                REFERRER_ID,
                cursor.getString(cursor.getColumnIndexOrThrow("referrerAgentId")),
            )

            // The two new columns exist and read null on every pre-v11 row:
            // null farmerUserId = self-owned, which is the existing behaviour.
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("farmerUserId")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("registeredByAgentId")))
        }
        db.close()
    }

    /**
     * The rest of the database is not in scope of this migration, and the test
     * says so out loud: a v10 row in a sibling table must come through untouched.
     */
    @Test
    fun migrate10To11_leavesOtherTablesAlone() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """
                INSERT INTO agents (
                    id, role, agentCode, region, ward, linkedContactId, linkedUserId,
                    endorsedById, status, createdAt, updatedAt, deletedAt,
                    pendingSync, syncAction
                ) VALUES (
                    'agent-1', 'extension_officer', 'EO-NANDI-041', 'Nandi', 'Chepterwai',
                    NULL, 'user-1', NULL, 'active',
                    '2026-08-10T09:00:00Z', '2026-08-10T09:00:00Z', NULL, 0, 'UPDATE'
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            true,
            DatabaseModule.MIGRATION_10_11,
        )

        db.query("SELECT agentCode, ward, role FROM agents WHERE id = 'agent-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EO-NANDI-041", cursor.getString(0))
            assertEquals("Chepterwai", cursor.getString(1))
            assertEquals("extension_officer", cursor.getString(2))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val FARM_ID = "6b1f0c4e-7c2a-4d63-9f21-1c9a5a3e8d10"
        const val REFERRER_ID = "b2c3d4e5-1111-4a2b-8c3d-9e0f1a2b3c4d"
    }
}
