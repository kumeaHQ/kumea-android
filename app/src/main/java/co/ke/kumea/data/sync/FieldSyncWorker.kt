package co.ke.kumea.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.ke.kumea.data.repository.FieldRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Concrete sync worker for Fields — a direct mirror of FarmSyncWorker (Ticket 3.1).
 * Not generic, not abstracted (per the Sprint 0 "don't generalise yet" bet).
 *
 * Runs push (local → server) then pull (server → local) on each execution.
 *
 * Status note (Ticket 3.1): like FarmSyncWorker, this class is NOT yet enqueued
 * anywhere. Field sync currently runs via manual pull-to-refresh in
 * FarmListViewModel (farms then fields). WorkManager scheduling — periodic +
 * NetworkType.CONNECTED, farms-before-fields in one wakeup (AC 15) — is a
 * deliberately deferred follow-up that will schedule BOTH workers together.
 * The skeleton exists so that future ticket is a wiring job, not a rewrite.
 */
@HiltWorker
class FieldSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fieldRepository: FieldRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Push local changes first so pending writes reach the server before
            // we overwrite with potentially stale pull data.
            fieldRepository.pushPending()

            // Pull server changes — server wins on timestamp.
            fieldRepository.pullSince()

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
