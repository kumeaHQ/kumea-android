package co.ke.kumea.data.sync

import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.local.SyncConflictEntity
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a failed push is classified and recorded.
 *
 * Every `SyncableRepository` routes its non-2xx responses through
 * [onFailure] instead of hand-rolling `if (code == 409) … else if (code == 403)`
 * chains, which is how the terminal set drifted apart across six repositories
 * in the first place — [RetryPolicy] explains what that cost.
 *
 * ── "NEVER DROP" IS THE POINT ───────────────────────────────────────────────
 *
 * A terminal rejection stops the row retrying, which is the whole benefit; but
 * the row's payload is written to `audit_sync_conflicts` in the same call, so
 * the farmer's data still exists on the device and can be re-pushed by hand once
 * the contract is fixed. That table is the "needs attention" queue — nothing new
 * had to be built, because it has always been the place rejected payloads go.
 *
 * It also means a wire-contract bug is now VISIBLE. The four incidents in
 * [RetryPolicy]'s table were all invisible: the repository reported the same
 * failure count every cycle and nothing distinguished "will succeed later" from
 * "will never succeed".
 */
@Singleton
class SyncRejectionRecorder @Inject constructor(
    private val syncConflictDao: SyncConflictDao,
) {

    /**
     * Classify a failed push, recording it when the verdict is final.
     *
     * @param entityType "farm", "note", "planting" … — the audit row's label.
     * @param entityId the row's UUID; also the key the 404 budget is counted on.
     * @param localPayload what the device tried to send. Stored verbatim so a
     *   terminal rejection can be inspected and re-pushed rather than guessed at.
     * @param verb "create" | "update" | "delete", for the audit label.
     * @return what the caller should do. On [PushDisposition.RETRY] the caller
     *   leaves `pendingSync = true`; otherwise it clears it.
     */
    suspend fun onFailure(
        entityType: String,
        entityId: String,
        localPayload: String,
        code: Int,
        serverPayload: String,
        verb: String,
    ): PushDisposition {
        // Only 404 needs history, and reading it costs a COUNT on an
        // effectively empty table — but skip it for every other status anyway,
        // because a push cycle should not query per failed row for no reason.
        val prior404s =
            if (code == 404) syncConflictDao.count404(entityId) else 0
        val disposition = RetryPolicy.classify(code, prior404s)

        // A RETRY'ing 404 is recorded too — that record IS the attempt counter,
        // and without it the budget could never be reached. Other retryable
        // statuses write nothing: a 5xx during a bad hour would fill the table.
        val shouldRecord = disposition != PushDisposition.RETRY || code == 404
        if (shouldRecord) {
            syncConflictDao.insert(
                SyncConflictEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = entityType,
                    entityId = entityId,
                    localPayload = localPayload,
                    serverPayload = serverPayload,
                    conflictType = RetryPolicy.conflictType(verb, code, disposition),
                    occurredAt = Clock.System.now().toString(),
                )
            )
        }
        return disposition
    }
}
