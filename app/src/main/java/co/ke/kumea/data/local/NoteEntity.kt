package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Note belongs to a Field (which belongs to a Farm). Like Farm/Field, the
 * client- and server-side share the same UUID primary key (Ticket 1.3), so
 * there is no separate serverId. Reuses the SyncAction enum from FarmEntity.
 *
 * Ticket 3.2 — first money on device. amountCents is a **Long** (native Room
 * INTEGER), never Double/Float: cents are integers and floats corrupt them
 * above 2^53. This is the money half of the precision discipline that acres
 * (String) established for area. On the wire amountCents travels as a String
 * (see NoteCreateRequest/NoteResponse); the Long↔String conversion happens only
 * at the network boundary inside NoteRepository. Display formatting (KES x.xx)
 * happens only at the very UI edge (see util/Money.kt).
 *
 * amountCents is an unsigned magnitude (>= 0). The `type` carries the sign at
 * rollup time (SALE +, PURCHASE −, ACTIVITY-with-cost −) — signed amounts are
 * never stored. PURCHASE/SALE require an amount; ACTIVITY may omit it.
 *
 * Ticket 2.1 — costCategory is an optional cost label feeding the server's
 * byCostCategory P&L breakdown. It's advisory metadata only: the `type` still
 * decides the sign, never this. Room persists the enum by name (TEXT, nullable).
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FieldEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            // CASCADE never fires during normal operation because soft delete is an
            // UPDATE, not a DELETE (same as Field → Farm).
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fieldId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val fieldId: String,
    val type: NoteType,
    val body: String,
    val amountCents: Long?,
    // Optional cost label (Ticket 2.1). Defaults to null — a note may carry no
    // category, and the sign is still derived from `type`, never from this.
    val costCategory: CostCategory? = null,
    /**
     * ── THE LINKED-RECORD PAIR (KWAP-03-V2 §2.5). DEVICE-ONLY. ───────────────
     *
     * [sourceType] is a [NoteSource] constant and [sourceId] the id of the row
     * that generated this note. Set together or not at all: a note with one and
     * not the other is meaningless.
     *
     * They exist so seed cost can be captured ONCE (decision 6). The planting
     * flow asks "what did the seed cost?" and writes a PURCHASE note; without a
     * link back, a farmer who doesn't realise the app already logged it adds
     * seed again by hand and "invested" silently doubles. With it, the ledger
     * renders the row read-only and taps through to the planting.
     *
     * 🔴 NOT ON THE WIRE, and this is not an oversight. The server's
     * `CreateNoteDto` whitelists id/fieldId/type/body/amountCents/costCategory/
     * occurredAt and runs `ValidationPipe({ forbidNonWhitelisted: true })` — two
     * extra keys would be a 400, and `NoteRepository.pushPending()` treats 400
     * as retryable, so every seed-cost note would sit at the head of the offline
     * queue for ever. That is the KWAP-01 `cropType`/`acres`/`useGps` bug and the
     * `kept`/`sold` bug, which is the rule CLAUDE.md states plainly: never add a
     * client field the server does not already accept.
     *
     * Consequence, handled in `NoteRepository.pullSince()`: the server cannot
     * return these, so a pull carries them forward from the local row instead of
     * writing null. Losing the link would un-hide the row in the ledger and
     * re-open the double-count.
     */
    val sourceType: String? = null,
    val sourceId: String? = null,
    val occurredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

enum class NoteType {
    /**
     * An observation. CARRIES NO MONEY from KWAP-03-V2 §2.7 onward — nodulation,
     * vigour, a WAO's field visit. The two ledgers are PURCHASE and SALE; the
     * activity log is not a ledger.
     *
     * The value STAYS in this enum (decision 9): dropping it would strand every
     * existing ACTIVITY row and force a data-rewriting migration of the kind
     * MIGRATION_11_12 had to do for `BIOFIX`. Rows written before the rule that
     * still carry an amount keep rendering; the repository just refuses new ones.
     */
    ACTIVITY,
    PURCHASE,
    SALE,
}

/**
 * What generated a note, for [NoteEntity.sourceType]. Device-only, like the
 * columns themselves. A note with no source was typed by a human.
 */
object NoteSource {
    /** Written by the planting flow's seed-cost question (§2.5). */
    const val PLANTING = "planting"
}

/**
 * Cost categories for the byCostCategory P&L breakdown (Ticket 2.1). Mirrors the
 * API's CostCategory enum EXACTLY (British spelling). Crosses the wire as the
 * enum name; null means uncategorised.
 *
 * "Exactly" is the whole contract, and a `BIOFIX` value that was never in the
 * server enum broke it from Build-3 until v12. The server's enum has only ever
 * been SEED / FERTILISER / LABOUR / SPRAY / TRANSPORT / OTHER — RB never added
 * the value the client-first picklist assumed — so every Kumea N purchase note
 * was pushed, rejected with a validation 400, and retried for ever, because 400
 * is not in the client's terminal set. MIGRATION_11_12 rewrites the stranded
 * rows to OTHER.
 *
 * So: NEVER add a value here that the server does not already accept. The cost
 * of being early is not a missing label, it is a permanently poisoned sync
 * queue. The picklist maps Kumea N to OTHER and Herbicide to SPRAY until RB
 * ships a real value — see [co.ke.kumea.ui.screen.note.PurchaseItem].
 */
enum class CostCategory {
    SEED,
    FERTILISER,
    LABOUR,
    SPRAY,
    TRANSPORT,
    OTHER,
}
