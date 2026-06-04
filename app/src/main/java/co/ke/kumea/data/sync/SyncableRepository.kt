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
 */
interface SyncableRepository {
    /** Push local changes to server. Throws on network failure — caller retries. */
    suspend fun pushPending()

    /** Pull server changes since last local update. Throws on network failure. */
    suspend fun pullSince()
}
