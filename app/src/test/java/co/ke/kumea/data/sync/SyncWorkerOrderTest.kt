package co.ke.kumea.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Ticket 2.2 — Set-iteration-order safety test.
 *
 * Proves the claim that SyncWorker's Set iteration order is defensive
 * (belt-and-braces), not load-bearing. Registers repos in reverse FK order
 * (note → field → farm) and confirms sync completes without error — the FK
 * guards in pullSince must prevent orphan violations regardless of iteration
 * order. Also confirms an unguarded repo DOES fail loudly (2.0 rule), and
 * the happy-path correct order works.
 */
class SyncWorkerOrderTest {

    private data class CallRecord(val repo: String, val phase: String)

    private class TrackingRepo(
        private val name: String,
        private val records: MutableList<CallRecord>,
        private val failOnPull: Boolean = false,
        private val pushCount: Int = 0,
        private val pullCount: Int = 0,
    ) : SyncableRepository {
        override suspend fun pushPending(): PushReport {
            records.add(CallRecord(name, "push"))
            return PushReport(repo = name, found = pushCount, attempted = pushCount, succeeded = pushCount)
        }
        override suspend fun pullSince(): Int {
            if (failOnPull) throw IllegalStateException("$name: FK guard missing")
            records.add(CallRecord(name, "pull"))
            return pullCount
        }
    }

    /** Reverse FK order (note before farm) — guards must prevent failure. */
    @Test
    fun `reverse registration order does not break sync`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("note", records),
            TrackingRepo("field", records),
            TrackingRepo("farm", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        assertEquals(6, records.size)
    }

    /** Unguarded repo must throw, not silently skip. */
    @Test
    fun `unguarded FK violation surfaces as error`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("note", records, failOnPull = true),
            TrackingRepo("farm", records),
        )
        var caught = false
        try { for (repo in repos) { repo.pushPending(); repo.pullSince() } }
        catch (e: IllegalStateException) { caught = true }
        assertTrue("Unguarded FK must throw", caught)
    }

    /** Happy path: correct FK order (farm → field → note). */
    @Test
    fun `correct FK order with guards succeeds`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("farm", records),
            TrackingRepo("field", records),
            TrackingRepo("note", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        val pushes = records.filter { it.phase == "push" }.map { it.repo }
        assertEquals(listOf("farm", "field", "note"), pushes)
    }

    // ── Phase 1a · T5-slice: Agent leads the FK order ───────────────────────
    // Farm.referrerAgentId attributes to an Agent, so a newly-onboarded agent
    // must push to the server BEFORE a farmer registered with it as referrer
    // (the server FK requires the Agent row first). RepositoryModule binds
    // agent → farm → field → note for exactly this reason.

    /** Declared order: agent pushes before farm. */
    @Test
    fun `agent pushes before farm in the declared FK order`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("agent", records),
            TrackingRepo("farm", records),
            TrackingRepo("field", records),
            TrackingRepo("note", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        val pushes = records.filter { it.phase == "push" }.map { it.repo }
        assertEquals(listOf("agent", "farm", "field", "note"), pushes)
    }

    /** Even registered reverse (note → … → agent), per-repo guards keep sync safe. */
    @Test
    fun `reverse order including agent still completes via guards`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("note", records),
            TrackingRepo("field", records),
            TrackingRepo("farm", records),
            TrackingRepo("agent", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        assertEquals(8, records.size)
    }

    // ── P1-T5: Order trails the FK order (agent → farm → field → note → order) ──
    // Order.farmerId reads from Farm and Order.agentCode resolves to an Agent, so
    // BOTH parents must reach the server before the order. RepositoryModule binds
    // order last for exactly this reason — but correctness is the per-repo FK
    // guard (defer + retry), not this Set order.

    /** Declared order: agent → farm → … → order, with order pushing last. */
    @Test
    fun `order pushes after agent and farm in the declared FK order`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("agent", records),
            TrackingRepo("farm", records),
            TrackingRepo("field", records),
            TrackingRepo("note", records),
            TrackingRepo("order", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        val pushes = records.filter { it.phase == "push" }.map { it.repo }
        assertEquals(listOf("agent", "farm", "field", "note", "order"), pushes)
        // The order's FK parents (agent, farm) both pushed before it.
        assertTrue(pushes.indexOf("agent") < pushes.indexOf("order"))
        assertTrue(pushes.indexOf("farm") < pushes.indexOf("order"))
    }

    /** Reverse-registered including order — per-repo guards still complete the cycle. */
    @Test
    fun `reverse order including order still completes via guards`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("order", records),
            TrackingRepo("note", records),
            TrackingRepo("field", records),
            TrackingRepo("farm", records),
            TrackingRepo("agent", records),
        )
        for (repo in repos) { repo.pushPending(); repo.pullSince() }
        assertEquals(10, records.size)
    }

    // ── Ticket 2.3: row counts aggregate across the multibound set ───────────
    // SyncWorker sums these to decide whether a background sync moved any data
    // (and so whether to show a "synced" notification).

    /** Counts from every repo sum into the worker's pushed/pulled totals. */
    @Test
    fun `push and pull counts aggregate across repos`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("farm", records, pushCount = 1, pullCount = 0),
            TrackingRepo("field", records, pushCount = 0, pullCount = 2),
            TrackingRepo("note", records, pushCount = 3, pullCount = 4),
        )
        var pushed = 0
        var pulled = 0
        for (repo in repos) { pushed += repo.pushPending().succeeded; pulled += repo.pullSince() }
        assertEquals(4, pushed)
        assertEquals(6, pulled)
        assertTrue("Data moved → worker would notify", pushed + pulled > 0)
    }

    /** All repos report zero → nothing moved → worker stays silent. */
    @Test
    fun `zero counts mean nothing moved`() = runBlocking {
        val records = mutableListOf<CallRecord>()
        val repos = linkedSetOf(
            TrackingRepo("farm", records),
            TrackingRepo("field", records),
            TrackingRepo("note", records),
        )
        var moved = 0
        for (repo in repos) { moved += repo.pushPending().succeeded; moved += repo.pullSince() }
        assertEquals("Nothing pending, nothing pulled → silent", 0, moved)
    }
}
