package co.ke.kumea.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.ke.kumea.data.repository.FarmRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Concrete sync worker for Farms — not generic, not abstracted.
 *
 * Runs push (local → server) then pull (server → local) on each execution.
 * WorkManager handles retry with exponential backoff on failure.
 *
 * Per Ticket 2.2 Rule 2: no generics, no SyncableEntity<T>. When Field sync
 * arrives in 2.3 it will be a separate FieldSyncWorker.
 */
@HiltWorker
class FarmSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val farmRepository: FarmRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Push local changes first — ensures pending writes reach the server
            // before we overwrite with potentially stale pull data.
            farmRepository.pushPending()

            // Pull server changes — server wins on timestamp.
            farmRepository.pullSince()

            Result.success()
        } catch (e: Exception) {
            // Network error or transient failure — WorkManager retries with
            // exponential backoff. No custom retry logic.
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
