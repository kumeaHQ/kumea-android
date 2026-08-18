package co.ke.kumea.data.sync

import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.NoteDao
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.NoteSource
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.data.local.SyncAction
import co.ke.kumea.data.local.SyncConflictDao
import co.ke.kumea.data.sync.SyncRejectionRecorder
import co.ke.kumea.data.local.SyncConflictEntity
import co.ke.kumea.data.remote.FakeKumeaApi
import co.ke.kumea.data.remote.dto.NoteCreateRequest
import co.ke.kumea.data.remote.dto.NoteResponse
import co.ke.kumea.data.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/**
 * Money-on-the-wire round-trip. amountCents is a Long in Room and a String on
 * the wire; a value above 2^53 must survive both directions byte-for-byte
 * (never a Number, never a Double). Uses plain fakes — see FakeKumeaApi for why
 * there is no mocking library here.
 */
class NoteSyncTest {

    // 2^53 + 1 cents = KES 90,071,992,547,409.93.
    private val aboveTwo53 = 9007199254740993L
    private val aboveTwo53Wire = "9007199254740993"

    private class FakeNoteDao : NoteDao {
        val upserts = mutableListOf<NoteEntity>()
        val upsertAlls = mutableListOf<List<NoteEntity>>()
        val markSyncedCalls = mutableListOf<Pair<String, String>>()
        var pending = emptyList<NoteEntity>()
        var latest: String? = null

        override fun getAllActive(): Flow<List<NoteEntity>> = flowOf(emptyList())
        override fun getActiveByField(fieldId: String): Flow<List<NoteEntity>> = flowOf(emptyList())
        override fun getActiveByFarm(farmId: String): Flow<List<NoteEntity>> = flowOf(emptyList())
        override suspend fun getPendingSync(): List<NoteEntity> = pending
        override suspend fun getById(id: String): NoteEntity? =
            (pending + upserts).lastOrNull { it.id == id }
        override suspend fun findBySource(sourceType: String, sourceId: String): NoteEntity? =
            (pending + upserts).lastOrNull { it.sourceType == sourceType && it.sourceId == sourceId }
        override suspend fun getByIds(ids: List<String>): List<NoteEntity> =
            (pending + upserts).filter { it.id in ids }
        override suspend fun getLatestUpdatedAt(): String? = latest
        override suspend fun upsertAll(notes: List<NoteEntity>) { upsertAlls.add(notes) }
        override suspend fun upsert(note: NoteEntity) { upserts.add(note) }
        override suspend fun markSynced(noteId: String, serverUpdatedAt: String) {
            markSyncedCalls.add(noteId to serverUpdatedAt)
        }
        override suspend fun markSyncedDelete(noteId: String, deletedAt: String) {}
    }

    private class NoOpConflictDao : SyncConflictDao {
        override suspend fun count404(entityId: String): Int =
            0
        override fun getTerminalRejections(): Flow<List<SyncConflictEntity>> = flowOf(emptyList())
        override suspend fun countTerminalRejections(): Int = 0
        override suspend fun insert(conflict: SyncConflictEntity) {}
    }

    private fun noteResponse(amountCents: String?, updatedAt: String = "t") = NoteResponse(
        id = "note-1", fieldId = "field-1", type = "SALE", body = "Harvest",
        amountCents = amountCents, occurredAt = "2026-05-30T00:00:00Z",
        createdAt = "t", updatedAt = updatedAt,
    )

    @Test
    fun `offline create stores a pending purchase with integer cents`() = runBlocking {
        val dao = FakeNoteDao()
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), FakeKumeaApi())

        val id = repository.createLocal(
            fieldId = "field-1",
            type = NoteType.PURCHASE,
            body = "Seed — 2 bags",
            amountCents = 200000L,
            occurredAt = "2026-05-30T00:00:00Z",
        )

        val captured = dao.upserts.single()
        assertEquals(id, captured.id)
        assertEquals(true, captured.pendingSync)
        assertEquals(SyncAction.CREATE, captured.syncAction)
        assertEquals(NoteType.PURCHASE, captured.type)
        assertEquals(200000L, captured.amountCents) // Long, never Double
    }

    @Test
    fun `push sends amountCents as a wire String above 2^53, byte-for-byte`() = runBlocking {
        val dao = FakeNoteDao().apply {
            pending = listOf(
                NoteEntity(
                    id = "note-1", fieldId = "field-1", type = NoteType.SALE, body = "Harvest",
                    amountCents = aboveTwo53, occurredAt = "2026-05-30T00:00:00Z",
                    createdAt = "t", updatedAt = "t", deletedAt = null,
                    pendingSync = true, syncAction = SyncAction.CREATE,
                ),
            )
        }
        var sent: NoteCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createNote(note: NoteCreateRequest): Response<NoteResponse> {
                sent = note
                return Response.success(noteResponse(aboveTwo53Wire, updatedAt = "t2"))
            }
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        val report = repository.pushPending()

        // Wire value is the exact decimal string — not a Number, no precision loss.
        assertEquals(aboveTwo53Wire, sent?.amountCents)
        assertEquals("note-1" to "t2", dao.markSyncedCalls.single())
        // Ticket 2.3 / P1-T5a: a successful push reports one row succeeded.
        assertEquals(1, report.succeeded)
    }

    @Test
    fun `pull parses a wire String above 2^53 back to the exact Long`() = runBlocking {
        val dao = FakeNoteDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getNotes(since: String?, includeDeleted: Boolean): List<NoteResponse> =
                listOf(noteResponse(aboveTwo53Wire))
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        val pulled = repository.pullSince()

        assertEquals(aboveTwo53, dao.upsertAlls.single().single().amountCents)
        // Ticket 2.3: a pull that applied one row reports one row moved.
        assertEquals(1, pulled)
    }

    // ── Ticket 2.1: costCategory crosses the wire as the enum name ────────────

    @Test
    fun `offline create stores the cost category, then push sends it as the enum name`() = runBlocking {
        val dao = FakeNoteDao()
        var sent: NoteCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createNote(note: NoteCreateRequest): Response<NoteResponse> {
                sent = note
                return Response.success(noteResponse("50000", updatedAt = "t2"))
            }
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.createLocal(
            fieldId = "field-1",
            type = NoteType.PURCHASE,
            body = "DAP fertiliser",
            amountCents = 50000L,
            occurredAt = "2026-05-30T00:00:00Z",
            costCategory = CostCategory.FERTILISER,
        )
        val stored = dao.upserts.single()
        assertEquals(CostCategory.FERTILISER, stored.costCategory)

        // Push the stored note: the category travels as its enum name.
        dao.pending = listOf(stored)
        repository.pushPending()
        assertEquals("FERTILISER", sent?.costCategory)
    }

    // ── KWAP-03-V2 §2.5: the source link, on the wire since 18 Aug ───────────
    //
    // These two keys were device-only from 14 to 18 Aug because the server's
    // CreateNoteDto did not whitelist them and forbidNonWhitelisted turns an
    // undeclared key into a 400. kumea-api 7cb03d2 whitelists AND stores them.
    //
    // What they protect: the ledger renders a linked seed Purchase read-only, so
    // a farmer cannot look at a row they don't recognise and enter seed cost a
    // second time. Lose the link and "invested" silently doubles.

    @Test
    fun `push sends the source link so a seed Purchase stays linked on every device`() = runBlocking {
        val dao = FakeNoteDao().apply {
            pending = listOf(
                NoteEntity(
                    id = "note-1", fieldId = "field-1", type = NoteType.PURCHASE,
                    body = "Seed — soybean", amountCents = 250000L,
                    costCategory = CostCategory.SEED,
                    sourceType = NoteSource.PLANTING, sourceId = "planting-uuid",
                    occurredAt = "2026-08-14", createdAt = "t", updatedAt = "t",
                    deletedAt = null, pendingSync = true, syncAction = SyncAction.CREATE,
                ),
            )
        }
        var sent: NoteCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createNote(note: NoteCreateRequest): Response<NoteResponse> {
                sent = note
                return Response.success(noteResponse("250000", updatedAt = "t2"))
            }
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.pushPending()

        assertEquals(NoteSource.PLANTING, sent?.sourceType)
        assertEquals("planting-uuid", sent?.sourceId)
    }

    @Test
    fun `a hand-typed note sends no source link at all`() = runBlocking {
        val dao = FakeNoteDao().apply {
            pending = listOf(
                NoteEntity(
                    id = "note-1", fieldId = "field-1", type = NoteType.SALE, body = "Sold 4 bags",
                    amountCents = 1440000L, occurredAt = "2026-12-01", createdAt = "t",
                    updatedAt = "t", deletedAt = null, pendingSync = true,
                    syncAction = SyncAction.CREATE,
                ),
            )
        }
        var sent: NoteCreateRequest? = null
        val api = object : FakeKumeaApi() {
            override suspend fun createNote(note: NoteCreateRequest): Response<NoteResponse> {
                sent = note
                return Response.success(noteResponse("1440000", updatedAt = "t2"))
            }
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.pushPending()

        assertNull(sent?.sourceType)
        assertNull(sent?.sourceId)
    }

    @Test
    fun `pull takes the link from the server now that the server has it`() = runBlocking {
        val dao = FakeNoteDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getNotes(since: String?, includeDeleted: Boolean): List<NoteResponse> =
                listOf(
                    noteResponse("250000").copy(
                        type = "PURCHASE",
                        sourceType = NoteSource.PLANTING,
                        sourceId = "planting-uuid",
                    ),
                )
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.pullSince()

        val pulled = dao.upsertAlls.single().single()
        assertEquals(NoteSource.PLANTING, pulled.sourceType)
        assertEquals("planting-uuid", pulled.sourceId)
    }

    @Test
    fun `pull keeps a local link the server does not know about`() = runBlocking {
        // The four-day window: notes written by the planting flow between 14 and
        // 18 Aug pushed BEFORE the server could accept these keys, so the server
        // holds them with a null source while this device holds the link.
        // Reading server.sourceType outright would write that null back, un-hide
        // the seed Purchase in the ledger, and re-open the double-count.
        val local = NoteEntity(
            id = "note-1", fieldId = "field-1", type = NoteType.PURCHASE,
            body = "Seed — soybean", amountCents = 250000L, costCategory = CostCategory.SEED,
            sourceType = NoteSource.PLANTING, sourceId = "planting-uuid",
            occurredAt = "2026-08-14", createdAt = "t", updatedAt = "t",
            deletedAt = null, pendingSync = false, syncAction = SyncAction.UPDATE,
        )
        val dao = FakeNoteDao().apply { upserts.add(local) }
        val api = object : FakeKumeaApi() {
            override suspend fun getNotes(since: String?, includeDeleted: Boolean): List<NoteResponse> =
                listOf(noteResponse("250000").copy(type = "PURCHASE"))  // no source fields
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.pullSince()

        val pulled = dao.upsertAlls.single().single()
        assertEquals("the local link must survive a server that has none", NoteSource.PLANTING, pulled.sourceType)
        assertEquals("planting-uuid", pulled.sourceId)
    }

    @Test
    fun `pull parses costCategory back to the enum, a null stays uncategorised`() = runBlocking {
        val dao = FakeNoteDao()
        val api = object : FakeKumeaApi() {
            override suspend fun getNotes(since: String?, includeDeleted: Boolean): List<NoteResponse> =
                listOf(
                    noteResponse("50000").copy(id = "labour", costCategory = "LABOUR"),
                    noteResponse(null).copy(id = "none", type = "ACTIVITY", costCategory = null),
                )
        }
        val repository = NoteRepository(dao, SyncRejectionRecorder(NoOpConflictDao()), api)

        repository.pullSince()

        val pulled = dao.upsertAlls.single()
        assertEquals(CostCategory.LABOUR, pulled.first { it.id == "labour" }.costCategory)
        assertEquals(null, pulled.first { it.id == "none" }.costCategory)
    }
}
