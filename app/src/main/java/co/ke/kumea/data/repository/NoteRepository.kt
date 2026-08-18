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
        sourceType: String? = null,
        sourceId: String? = null,
    ): String {
        // §2.7: the activity log carries observations, not money. Enforced here
        // rather than only in the form, because the form is not the only caller
        // and a rule that lives in a composable is a rule one screen keeps.
        require(type != NoteType.ACTIVITY || amountCents == null) {
            "an ACTIVITY note carries no money — the two ledgers are PURCHASE and SALE (§2.7)"
        }
        require(type != NoteType.ACTIVITY || costCategory == null) {
            "an ACTIVITY note carries no cost category — see NoteType.ACTIVITY"
        }
        val now = Clock.System.now().toString()
        val id = UUID.randomUUID().toString()
        val note = NoteEntity(
            id = id,
            fieldId = fieldId,
            type = type,
            body = body,
            amountCents = amountCents,
            costCategory = costCategory,
            sourceType = sourceType,
            sourceId = sourceId,
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

    /** The note a planting generated, if it wrote one (§2.5). */
    suspend fun findBySource(sourceType: String, sourceId: String): NoteEntity? =
        noteDao.findBySource(sourceType, sourceId)

    /**
     * Update a note locally (offline-first).
     *
     * The read was `getPendingSync().find { it.id == id }`, which could only see
     * rows that had NOT yet synced — an already-pushed note silently ignored the
     * edit and returned as though it had worked. That gap was tolerable while no
     * UI exercised a synced-row edit; §2.5 exercises it directly, because
     * changing a planting's seed cost has to update a Purchase note that has
     * very likely synced already. Reads by id now.
     */
    suspend fun updateLocal(
        id: String,
        type: NoteType? = null,
        body: String? = null,
        amountCents: Long? = null,
        occurredAt: String? = null,
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
                            // §2.5, on the wire since 18 Aug. Both null for a
                            // hand-typed note.
                            sourceType = note.sourceType,
                            sourceId = note.sourceId,
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
                            sourceType = note.sourceType,
                            sourceId = note.sourceId,
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

        // sourceType/sourceId ARE on the wire now (kumea-api 7cb03d2, deployed
        // 18 Aug), so the server value leads — but the local one still backs it
        // up, and that fallback is not vestigial.
        //
        // Notes written by the planting flow between 14 and 18 Aug pushed BEFORE
        // the server could accept these keys, so the server holds those rows
        // with a null source while this device holds the link. Reading
        // `server.sourceType` outright would write that null back, un-hide the
        // seed Purchase in the ledger, and re-open the double-count §2.5 exists
        // to prevent. `server.x ?: local?.x` keeps them.
        //
        // Nothing ever clears a link — a cleared seed cost soft-deletes the
        // whole note (PlantingRepository.updateLocal) — so the fallback can
        // never resurrect a link that was deliberately removed.
        //
        // NOT SELF-HEALING, and knowingly so: those rows keep the link on the
        // device that wrote them and look like ordinary editable purchases
        // anywhere else. Re-pushing them would mean marking synced rows pending
        // from inside a pull, which is a data migration wearing a sync's
        // clothes. Small, known population; new rows carry the link properly.
        val existing = noteDao.getByIds(serverNotes.map { it.id }).associateBy { it.id }

        val localEntities = serverNotes.map { server ->
            val local = existing[server.id]
            NoteEntity(
                id = server.id,
                fieldId = server.fieldId,
                type = NoteType.valueOf(server.type),
                body = server.body,
                // wire String → Long. Never Double.
                amountCents = server.amountCents?.toLong(),
                // wire String → enum (by name); null stays uncategorised.
                costCategory = server.costCategory?.let { CostCategory.valueOf(it) },
                sourceType = server.sourceType ?: local?.sourceType,
                sourceId = server.sourceId ?: local?.sourceId,
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
