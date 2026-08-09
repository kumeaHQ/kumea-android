package co.ke.kumea.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-T5a — the per-repo push report is the diagnostic that ends the
 * "0 pushed, no error" silent catch. These pin the mandated one-line format
 *
 *   [Repo]: found N, attempted M, succeeded X, failed Y (status), deferred Z (reason)
 *
 * and prove the builder routes every outcome into exactly one bucket.
 */
class PushReportTest {

    @Test
    fun `a clean push line shows the counts with no status or reason`() {
        val report = PushReport(repo = "Agents", found = 2, attempted = 2, succeeded = 2)
        assertEquals("Agents: found 2, attempted 2, succeeded 2, failed 0", report.line())
    }

    @Test
    fun `a deferred order names the reason and is not counted as attempted`() {
        val report = PushReport(
            repo = "Orders",
            found = 1,
            deferred = 1,
            deferReasons = listOf("FK parent not synced yet"),
        )
        assertEquals(
            "Orders: found 1, attempted 0, succeeded 0, failed 0, deferred 1 (FK parent not synced yet)",
            report.line(),
        )
    }

    @Test
    fun `a failed push surfaces the HTTP status — the stale-token signature`() {
        val report = PushReport(
            repo = "Agents",
            found = 2,
            attempted = 2,
            succeeded = 0,
            failed = 2,
            failures = listOf("401", "401"),
        )
        assertEquals("Agents: found 2, attempted 2, succeeded 0, failed 2 (401, 401)", report.line())
    }

    @Test
    fun `the builder routes every outcome into exactly one bucket`() {
        val builder = PushReportBuilder("Orders")
        builder.found = 3
        builder.succeeded()
        builder.failed("500")
        builder.deferred("FK parent not synced yet")
        val report = builder.build()

        assertEquals(3, report.found)
        // attempted = succeeded + failed; a deferral is waiting, not attempted.
        assertEquals(2, report.attempted)
        assertEquals(1, report.succeeded)
        assertEquals(1, report.failed)
        assertEquals(1, report.deferred)
        assertEquals(listOf("500"), report.failures)
        assertEquals(listOf("FK parent not synced yet"), report.deferReasons)
    }
}
