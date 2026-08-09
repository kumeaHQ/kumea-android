package co.ke.kumea.data.sync

/**
 * Contract for an offline-first repository that syncs with the server (Ticket 2.2).
 *
 * pushPending() + pullSince() is the pattern mechanically copied across
 * FarmRepository, FieldRepository, and NoteRepository. Extracting this
 * interface lets a single SyncWorker drive all three via Hilt multibinding
 * instead of maintaining three identical Worker classes.
 *
 * Adding a fourth entity (Weather, Ticket 2.4) is a one-line
 * `: SyncableRepository` on its repository — no new worker file needed.
 *
 * pushPending returns a [PushReport] (P1-T5a) so the worker can log a per-repo
 * found/attempted/succeeded/failed/deferred breakdown — never "0 pushed, no
 * error" again. pullSince still returns a row count (the silent-catch bug was in
 * the push path, and a non-2xx pull surfaces as a thrown HttpException already).
 */
interface SyncableRepository {
    /**
     * Push local changes to server. Throws on network failure — caller retries.
     * Every non-2xx is classified into the [PushReport] (failed+status or
     * deferred+reason); nothing is silently swallowed.
     */
    suspend fun pushPending(): PushReport

    /**
     * Pull server changes since last local update. Throws on network failure.
     * @return the number of server rows applied locally (0 if up to date).
     */
    suspend fun pullSince(): Int
}
