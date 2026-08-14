package co.ke.kumea.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.7 and §2.5 as type-level facts, checkable without a database.
 *
 * The behavioural halves (the repository's `require`, the one-note-per-planting
 * rule) live in PlantingRepository/NoteRepository and need Room; these pin the
 * decisions that a future edit would most plausibly undo by accident.
 */
class ActivityMoneyRuleTest {

    @Test
    fun `ACTIVITY stays in the enum`() {
        // Decision 9. Removing it would strand every existing activity row and
        // force a data-rewriting migration of the kind MIGRATION_11_12 needed
        // for BIOFIX. The rule is "no NEW money on activities", not "no
        // activities" — the log is where mid-season research observations live.
        assertTrue(NoteType.entries.any { it == NoteType.ACTIVITY })
        assertEquals(3, NoteType.entries.size)
    }

    @Test
    fun `the two ledgers are PURCHASE and SALE`() {
        // §2.6. If a fourth money type ever appears, the picker, the sign
        // prefix and the ledger colours all need a deliberate decision.
        val money = NoteType.entries.filter { it != NoteType.ACTIVITY }
        assertEquals(listOf(NoteType.PURCHASE, NoteType.SALE), money)
    }

    @Test
    fun `the cost categories still match the server's enum exactly`() {
        // The rule CLAUDE.md states plainly: never add a value the server does
        // not already accept. BIOFIX cost a permanently poisoned sync queue.
        assertEquals(
            setOf("SEED", "FERTILISER", "LABOUR", "SPRAY", "TRANSPORT", "OTHER"),
            CostCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a linked note names its source`() {
        // §2.5's linkage. The string value crosses no wire (the server does not
        // whitelist these columns) but it IS persisted, so changing it would
        // orphan every existing seed Purchase and re-open the double-count.
        assertEquals("planting", NoteSource.PLANTING)
    }
}
