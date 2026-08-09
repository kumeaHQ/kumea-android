package co.ke.kumea.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Generic sync worker — replaces FarmSyncWorker, FieldSyncWorker, and
 * NoteSyncWorker (Ticket 2.2). Injects a Set<SyncableRepository> via Hilt
 * multibinding and runs push-then-pull across all repositories.
 *
 * Set iteration is deterministic (LinkedHashSet from Hilt preserves the
 * @Binds @IntoSet declaration order in RepositoryModule). The declaration
 * order is farm → field → note, matching FK dependency. Each repository's
 * pullSince() also has its own FK guard (e.g. NoteRepository filters out
 * orphaned fields), so order is belt-and-braces rather than load-bearing.
 *
 * Adding a new syncable entity (Weather, Ticket 2.4): add one @Binds @IntoSet
 * in RepositoryModule + implement SyncableRepository on the new repo.
 * No new worker class, no wiring changes.
 *
 * Ticket 2.3 wires this worker into WorkManager (connectivity-triggered +
 * 6-hour periodic — see SyncScheduler). Because every SyncWorker run is a
 * *background* run (pull-to-refresh calls the repositories directly, not this
 * worker), it owns the background-only behaviour the 2.3 spec requires:
 *   - notify on success only when data actually moved (≥1 row pushed/pulled);
 *   - log every failure at error level;
 *   - notify on *sustained* failure (all retries exhausted), not on a single
 *     transient hiccup — a farmer in a signal dead-zone shouldn't be spammed.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repositories: Set<@JvmSuppressWildcards SyncableRepository>,
    private val notifier: SyncNotifier,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            var pushed = 0
            var pulled = 0
            for (repo in repositories) {
                val report = repo.pushPending()
                // P1-T5a: per-repo push report. This is the line that ends the
                // "0 pushed, no error" silent-catch — a run that leaves rows
                // pending now says exactly why (failed+status / deferred+reason).
                Log.i(TAG, report.line())
                pushed += report.succeeded
                pulled += repo.pullSince()
            }
            if (pushed + pulled > 0) {
                Log.i(TAG, "Background sync moved data: $pushed pushed, $pulled pulled")
                notifier.showSyncSuccess(pushed, pulled)
            }
            Result.success()
        } catch (e: CancellationException) {
            // Cooperative cancellation (e.g. constraint no longer met). Never
            // swallow it as a failure — let the coroutine machinery handle it.
            throw e
        } catch (e: Exception) {
            // 2.0 rule: background failures stay loud. Log every attempt at error
            // level; only escalate to a user-facing notification once WorkManager
            // has exhausted its retries (a single transient 2G failure is normal).
            Log.e(TAG, "Background sync failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Log.e(TAG, "Background sync failed after $MAX_RETRIES retries — notifying")
                notifier.showSyncError()
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        /** Retries after the first attempt before giving up (preserves 2.2 behaviour). */
        private const val MAX_RETRIES = 3
    }
}
