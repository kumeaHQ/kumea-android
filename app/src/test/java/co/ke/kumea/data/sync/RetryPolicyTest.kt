package co.ke.kumea.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four incidents that motivated the classifier, as regression cases.
 *
 * Each row in [RetryPolicy]'s table was a real bug that put a row at the head of
 * the offline queue and re-sent it on every sync cycle, for ever. These assert
 * that none of them can recur silently.
 */
class RetryPolicyTest {

    @Test
    fun `a malformed body is terminal — the kept-sold and cropType bugs`() {
        // `kept`/`sold` vs `keptQuantity`/`soldQuantity`, and cropType/acres/
        // useGps against forbidNonWhitelisted. Both were 400s retried for ever.
        assertEquals(PushDisposition.TERMINAL, RetryPolicy.classify(400))
    }

    @Test
    fun `a forbidden write is terminal — the on-behalf role and ward rejections`() {
        assertEquals(PushDisposition.TERMINAL, RetryPolicy.classify(403))
    }

    @Test
    fun `a missing route is terminal, but only after the budget is spent`() {
        // The plantings case. A cold container or an in-progress deploy also
        // answers 404, so the first attempts retry...
        assertEquals(PushDisposition.RETRY, RetryPolicy.classify(404, prior404s = 0))
        assertEquals(PushDisposition.RETRY, RetryPolicy.classify(404, prior404s = 1))
        // ...and the third gives up, because a route that does not exist does
        // not appear because a phone asked again.
        assertEquals(PushDisposition.TERMINAL, RetryPolicy.classify(404, prior404s = 2))
        assertEquals(PushDisposition.TERMINAL, RetryPolicy.classify(404, prior404s = 9))
    }

    @Test
    fun `the 404 budget is exactly MAX_404_ATTEMPTS attempts`() {
        val attempts = generateSequence(0) { it + 1 }
            .takeWhile { RetryPolicy.classify(404, it) == PushDisposition.RETRY }
            .count() + 1          // the attempt that turned terminal
        assertEquals(RetryPolicy.MAX_404_ATTEMPTS, attempts)
    }

    @Test
    fun `409 stays a conflict, not a terminal rejection`() {
        // Different handling downstream: server-wins replaces the local row,
        // where a terminal rejection preserves it for re-pushing.
        assertEquals(PushDisposition.CONFLICT, RetryPolicy.classify(409))
    }

    @Test
    fun `401 retries — clearing the session here would be the AC22 violation`() {
        // TokenAuthenticator refreshes and the next cycle succeeds. Only an
        // explicit 401 from /auth/me clears a session, never a push.
        assertEquals(PushDisposition.RETRY, RetryPolicy.classify(401))
    }

    @Test
    fun `transient server and network failures retry`() {
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals("$code must retry", PushDisposition.RETRY, RetryPolicy.classify(code))
        }
    }

    @Test
    fun `the audit label distinguishes terminal from retryable and conflict`() {
        assertEquals("create_terminal_400", RetryPolicy.conflictType("create", 400, PushDisposition.TERMINAL))
        assertEquals("update_409", RetryPolicy.conflictType("update", 409, PushDisposition.CONFLICT))
        // The '%_404' suffix is what SyncConflictDao.count404 matches on, so the
        // retryable 404 label has to end in the code — the budget depends on it.
        assertEquals("create_retry_404", RetryPolicy.conflictType("create", 404, PushDisposition.RETRY))
    }
}
