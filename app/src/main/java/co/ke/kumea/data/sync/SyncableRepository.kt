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
 * Both methods return the number of rows they actually moved (Ticket 2.3). This
 * is additive instrumentation only — the sync semantics are unchanged — and lets
 * the SyncWorker decide whether a background sync moved any data (and so whether
 * to show a "synced" notification) without reaching past the abstraction into the
 * DAOs. A new syncable entity inherits this for free.
 */
interface SyncableRepository {
    /**
     * Push local changes to server. Throws on network failure — caller retries.
     * @return the number of pending rows successfully pushed (0 if none pending).
     */
    suspend fun pushPending(): Int

    /**
     * Pull server changes since last local update. Throws on network failure.
     * @return the number of server rows applied locally (0 if up to date).
     */
    suspend fun pullSince(): Int
}
