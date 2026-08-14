package co.ke.kumea.data.repository

import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.remote.KumeaApi
import co.ke.kumea.data.remote.dto.NoteCreateRequest
import co.ke.kumea.data.remote.dto.NoteUpdateRequest
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import co.ke.kumea.data.sync.PushDisposition
import co.ke.kumea.data.sync.PushReport
import co.ke.kumea.data.sync.PushReportBuilder
import co.ke.kumea.data.sync.SyncRejectionRecorder
import co.ke.kumea.data.sync.SyncableRepository

/**
 * Offline-first Note sync — a direct copy of FieldRepository (Ticket 3.2).
 *
 * The only substantive differences from Field are:
 *   - createLocal takes a fieldId + type + occurredAt (Note belongs to Field)
 *   - the API surface is /notes
 *   - **money:** amountCents is a Long in the entity (native), but travels as a
 *     String on the wire. The Long↔String conversion is done HERE and only here
 *     (amountCents?.toString() out, ?.toLong() in). Never Double anywhere.
 * Everything else is a mechanical rename of the Field copy.
 */
@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val rejections: SyncRejectionRecorder,
    private val api: KumeaApi,
) : SyncableRepository {
    /** Observe all active notes (live, via Room Flow). */
    fun getAllActive(): Flow<List<NoteEntity>> = noteDao.getAllActive()

    /** Observe active notes for a single field. */
    fun getActiveByField(fieldId: String): Flow<List<NoteEntity>> = noteDao.getActiveByField(fieldId)

    /** Observe active notes across all of a farm's fields. */
    fun getActiveByFarm(farmId: String): Flow<List<NoteEntity>> = noteDao.getActiveByFarm(farmId)

    /**
     * Create a note locally (offline-first). amountCents is already-parsed integer
     * cents (Long) the caller validated via Money.parseToCents — stored verbatim,
     * never re-parsed from a float. Returns the generated UUID.
     */
    suspend fun createLocal(
        fieldId: String,
        type: NoteType,
        body: String,
        amountCents: Long?,
        occurredAt: String,
        costCategory: CostCategory? = null,
    ): String {
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val note = NoteEntity(
            id = id,
            fieldId = fieldId,
            type = type,
            body = body,
            amountCents = amountCents,
            costCategory = costCategory,
            occurredAt = occurredAt,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            pendingSync = true,
            syncAction = SyncAction.CREATE,
        )
        noteDao.upsert(note)
        return id
    }

    /**
     * Update a note locally (offline-first).
     *
     * PRODUCTION BUG FIX. The read was `getPendingSync().find { it.id == id }`,
     * which could only ever see rows that had NOT yet synced. On a shipped
     * handset, editing a note the server already had did nothing at all — and
     * returned as though it had worked, so nothing surfaced. Reads by id now.
     */
    suspend fun updateLocal(
        id: String,
        type: NoteType?,
        body: String?,
        amountCents: Long?,
        occurredAt: String?,
        costCategory: CostCategory? = null,
    ) {
        val now = Clock.System.now().toString()
        var note = noteDao.getById(id) ?: return
        note = note.copy(
            type = type ?: note.type,
            body = body ?: note.body,
            amountCents = amountCents ?: note.amountCents,
            costCategory = costCategory ?: note.costCategory,
            occurredAt = occurredAt ?: note.occurredAt,
            updatedAt = now,
            pendingSync = true,
            // An unsynced CREATE must stay a CREATE — flipping it would PATCH an
            // id the server has never seen (404, retried for ever).
            syncAction = if (note.syncAction == SyncAction.CREATE && note.pendingSync) {
                SyncAction.CREATE
            } else {
                SyncAction.UPDATE
            },
        )
        noteDao.upsert(note)
    }

    /** Soft-delete a note locally (offline-first). Same by-id fix as above. */
    suspend fun deleteLocal(id: String) {
        val now = Clock.System.now().toString()
        var note = noteDao.getById(id) ?: return
        note = note.copy(
            deletedAt = now,
            updatedAt = now,
            pendingSync = true,
            syncAction = SyncAction.DELETE,
        )
        noteDao.upsert(note)
    }

    /**
     * Push all pending local changes to the server.
     * Called by the sync trigger (manual refresh today; SyncWorker later).
     */
    override suspend fun pushPending(): PushReport {
        val pending = noteDao.getPendingSync()
        val report = PushReportBuilder("Notes")
        report.found = pending.size
        for (note in pending) {
            when (note.syncAction) {
                SyncAction.CREATE -> {
                    val response = api.createNote(
                        NoteCreateRequest(
                            id = note.id,
                            fieldId = note.fieldId,
                            type = note.type.name,
                            body = note.body,
                            // Long → wire String. Null stays null (ACTIVITY w/o cost).
                            amountCents = note.amountCents?.toString(),
                            // Enum → wire String (the name). Null = uncategorised.
                            costCategory = note.costCategory?.name,
                            occurredAt = note.occurredAt,
                        )
                    )
                    if (response.isSuccessful) {
                        noteDao.markSynced(note.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(note, response.code(), response.errorBody()?.string(), "create")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.UPDATE -> {
                    val response = api.updateNote(
                        note.id,
                        NoteUpdateRequest(
                            type = note.type.name,
                            body = note.body,
                            amountCents = note.amountCents?.toString(),
                            costCategory = note.costCategory?.name,
                            occurredAt = note.occurredAt,
                            updatedAt = note.updatedAt,
                        )
                    )
                    if (response.isSuccessful) {
                        noteDao.markSynced(note.id, response.body()!!.updatedAt)
                        report.succeeded()
                    } else {
                        applyFailure(note, response.code(), response.errorBody()?.string(), "update")
                        report.failed(response.code().toString())
                    }
                }
                SyncAction.DELETE -> {
                    val response = api.deleteNote(note.id)
                    if (response.isSuccessful) {
                        val now = Clock.System.now().toString()
                        noteDao.markSyncedDelete(note.id, note.deletedAt ?: now)
                        report.succeeded()
                    } else {
                        applyFailure(note, response.code(), response.errorBody()?.string(), "delete")
                        report.failed(response.code().toString())
                    }
                }
            }
        }
        return report.build()
    }

    /**
     * Pull server changes since the latest local updatedAt.
     *
     * Must run AFTER the field pull in a sync cycle: a note's CASCADE foreign key
     * requires its parent field row to exist locally first (farms → fields →
     * notes). amountCents is parsed from the wire String to Long here — never via
     * Double, so values above 2^53 survive intact.
     */
    override suspend fun pullSince(): Int {
        val since = noteDao.getLatestUpdatedAt()
        // includeDeleted = true so soft-deleted rows reconcile on other devices
        // (same as Field; see FieldRepository.pullSince).
        val serverNotes = try {
            api.getNotes(since = since, includeDeleted = true)
        } catch (e: Exception) {
            throw e
        }

        if (serverNotes.isEmpty()) return 0

        val localEntities = serverNotes.map { server ->
            NoteEntity(
                id = server.id,
                fieldId = server.fieldId,
                type = NoteType.valueOf(server.type),
                body = server.body,
                // wire String → Long. Never Double.
                amountCents = server.amountCents?.toLong(),
                // wire String → enum (by name); null stays uncategorised.
                costCategory = server.costCategory?.let { CostCategory.valueOf(it) },
                occurredAt = server.occurredAt,
                createdAt = server.createdAt,
                updatedAt = server.updatedAt,
                deletedAt = server.deletedAt,
                pendingSync = false,
                syncAction = SyncAction.UPDATE,
            )
        }

        // Same invariant as Field/Farm: never let pull clobber a row that push
        // hasn't reconciled yet.
        val pendingIds = noteDao.getPendingSync().map { it.id }.toSet()
        val cleanEntities = localEntities.filter { it.id !in pendingIds }
        if (cleanEntities.isNotEmpty()) {
            noteDao.upsertAll(cleanEntities)
        }
        return cleanEntities.size
    }

    /** Single exit for every non-2xx — classification lives in [RetryPolicy]. */
    private suspend fun applyFailure(note: NoteEntity, code: Int, serverBody: String?, verb: String) {
        val disposition = rejections.onFailure(
            entityType = "note",
            entityId = note.id,
            localPayload = note.toString(),
            code = code,
            serverPayload = serverBody ?: "{}",
            verb = verb,
        )
        if (disposition != PushDisposition.RETRY) {
            noteDao.upsert(note.copy(pendingSync = false))
        }
    }
}
