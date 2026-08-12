package co.ke.kumea.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `CostCategory` crosses the wire as the enum NAME, so this client enum is not a
 * display list — it is half of a contract with the server's Prisma enum.
 *
 * A value the server does not accept is not a cosmetic mismatch. It is a
 * validation 400 on every push of a note carrying it, and `NoteRepository`
 * treats 400 as retryable, so the row sits at the head of the offline queue and
 * is re-sent for ever. That is exactly what `BIOFIX` did from Build-3 until v12:
 * shipped client-first on the promise that RB would add it server-side, and RB
 * never did.
 *
 * This test is the thing that would have caught it. If it fails, the fix is to
 * change the CLIENT — or to confirm the server enum first and change this list
 * in the same breath as the deploy, never ahead of it.
 *
 * Source of truth: `prisma/schema.prisma`, `enum CostCategory`.
 */
class CostCategoryContractTest {

    @Test
    fun `every category is one the server's enum accepts`() {
        val serverEnum = listOf("SEED", "FERTILISER", "LABOUR", "SPRAY", "TRANSPORT", "OTHER")
        assertEquals(serverEnum, CostCategory.entries.map { it.name })
    }
}
