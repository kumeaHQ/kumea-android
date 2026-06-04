package co.ke.kumea.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repositories: Set<@JvmSuppressWildcards SyncableRepository>,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        for (repo in repositories) {
            repo.pushPending()
            repo.pullSince()
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.failure()
        }
    }
}
