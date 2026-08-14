package co.ke.kumea.data.sync

/**
 * WHAT TO DO WITH A NON-2xx PUSH.
 *
 * ── THE ROOT CAUSE THIS EXISTS TO FIX (14 Aug 2026) ─────────────────────────
 *
 * Four separate incidents in this project were one mechanism failing four times:
 *
 * | Incident                                   | Code | Terminal before? |
 * |--------------------------------------------|------|------------------|
 * | `kept`/`sold` wire-name mismatch            | 400  | no → forever     |
 * | `cropType`/`acres`/`useGps` not whitelisted | 400  | no → forever     |
 * | `notes.sourceType`/`sourceId` not whitelisted | 400 | no → forever    |
 * | `plantings` push with no server route        | 404  | no → forever     |
 *
 * The client's terminal set was literally `{403}`. Everything else stayed
 * `pendingSync = true` and was re-sent on every cycle — so a malformed body sat
 * at the head of the offline queue for ever, blocking nothing but succeeding
 * never, and reporting the same status on every sync. Combined with the server's
 * `ValidationPipe({ forbidNonWhitelisted: true })`, every client field that ran
 * ahead of its server counterpart became a landmine, and "just don't send it"
 * became the standing workaround — which is why three datasets are device-only
 * today.
 *
 * **400 and 404 are structurally terminal.** A body the server refuses to parse
 * is not going to parse on the ninth attempt, and a route that does not exist
 * does not appear because a phone asked again.
 *
 * ── TERMINAL MEANS MARK AND SURFACE, NEVER DROP ─────────────────────────────
 *
 * A terminal row stops retrying, but its payload is written to
 * `audit_sync_conflicts` first — the farmer's data is never silently discarded
 * because the wire contract was wrong. See [SyncRejectionRecorder].
 *
 * ── WHY 404 IS BOUNDED RATHER THAN IMMEDIATE ────────────────────────────────
 *
 * A 404 has two very different causes. A missing route never heals. But a cold
 * container, a proxy that has not finished routing, or a deploy in progress all
 * return 404 transiently, and treating the first one as fatal would strand rows
 * that a retry ten minutes later would have pushed cleanly. Bounded retry
 * separates them: transient 404s clear within [MAX_404_ATTEMPTS], a missing
 * route does not.
 */
enum class PushDisposition {
    /** Retry next cycle — network, 5xx, 401, timeout, or a 404 still inside its budget. */
    RETRY,

    /** Server won on `updatedAt` (409). Local discarded, recorded, stops retrying. */
    CONFLICT,

    /** Structurally unfixable by retrying. Recorded, surfaced, stops retrying. */
    TERMINAL,
}

object RetryPolicy {

    /**
     * How many times a 404 is retried before it is called terminal. Three
     * cycles is long enough to outlast a cold start or a deploy and short
     * enough that a genuinely missing route surfaces the same day.
     */
    const val MAX_404_ATTEMPTS = 3

    /**
     * @param code the HTTP status the push came back with.
     * @param prior404s how many times THIS row has already been 404'd, from the
     *   audit table. Ignored for every other status.
     */
    fun classify(code: Int, prior404s: Int = 0): PushDisposition = when {
        code == 409 -> PushDisposition.CONFLICT

        // Malformed or forbidden. No amount of retrying changes either.
        code == 400 || code == 403 -> PushDisposition.TERMINAL

        // 404: transient until proven otherwise. `prior404s` counts attempts
        // already recorded, so the Nth attempt is terminal.
        code == 404 ->
            if (prior404s + 1 >= MAX_404_ATTEMPTS) PushDisposition.TERMINAL
            else PushDisposition.RETRY

        // 401 is deliberately RETRY, not terminal: TokenAuthenticator refreshes
        // and the next cycle succeeds. Clearing the session here would be the
        // AC22 violation this project has a standing rule against.
        else -> PushDisposition.RETRY
    }

    /** Audit `conflictType` for a rejection, e.g. "create_terminal_400". */
    fun conflictType(verb: String, code: Int, disposition: PushDisposition): String = when (disposition) {
        PushDisposition.CONFLICT -> "${verb}_409"
        PushDisposition.TERMINAL -> "${verb}_terminal_$code"
        PushDisposition.RETRY -> "${verb}_retry_$code"
    }
}
