package co.ke.kumea.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.ke.kumea.data.repository.NoteRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Concrete sync worker for Notes — a direct mirror of FieldSyncWorker (Ticket 3.2).
 * Not generic, not abstracted (per the Sprint 0 "don't generalise yet" bet;
 * Notes is concrete implementation #3, which is the signal to extract a generic
 * worker in Sprint 2 — not before).
 *
 * Status note: like FarmSyncWorker and FieldSyncWorker, this class is NOT yet
 * enqueued anywhere. Note sync currently runs via manual pull-to-refresh in
 * NoteListViewModel (farms → fields → notes, parents before children).
 * WorkManager scheduling that wakes all three workers in one cycle is the same
 * deferred follow-up tracked for Farm/Field.
 */
@HiltWorker
class NoteSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val noteRepository: NoteRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            noteRepository.pushPending()
            noteRepository.pullSince()
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
