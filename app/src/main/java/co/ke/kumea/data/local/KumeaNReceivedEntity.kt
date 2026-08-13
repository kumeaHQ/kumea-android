package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * "This farmer received Kumea N" — the interim record (KWAP-03 §7).
 *
 * A DELIBERATELY NARROW SHIM, and the narrowness is the design. KWAP-02 (stock
 * and traceability: catalogue, batches, agent balances, grants, the 403 refusal
 * machinery) is paused, but sachets are going out now and Zone 1 of the farm
 * page has nothing to show. This table is roughly the cost of the free-text note
 * it replaces.
 *
 * WHY NOT A NOTE. The 13 Aug lock says every distribution record carries its
 * batch number. `"gave 3 sachets"` in a `NoteEntity.body` has no batch, cannot
 * be reconciled at season end, and turns the KWAP-02 backfill into archaeology.
 * Same effort in the field, structured shape, and the backfill becomes a script:
 * map `(strainCode, packSizeG, batchNumber) → batchId`, move the rows into
 * `stock_distributions`, drop this table.
 *
 * WHAT IS KNOWINGLY LOST: stock balances. Nobody knows what an agent is holding
 * until KWAP-02 resumes. That is the accepted cost of the pause, recorded here
 * so it is not discovered in October.
 *
 * THIS IS NOT AN ORDER AND MUST NEVER BECOME ONE. No `agentId`, no
 * `referrerAgentId`, no price, no quantity-times-unit-price anywhere. The
 * commission engine is live and accrues backdated to 1 June, and these are ~395
 * research farmers who were GIVEN the product; a commercial field on this row is
 * money owed to agents who sold nothing. [recordedByAgentId] is provenance —
 * who handed it over — in the same sense as `FarmEntity.registeredByAgentId`,
 * and is derived from the caller rather than picked from a list.
 */
@Entity(
    tableName = "kumea_n_received",
    foreignKeys = [
        ForeignKey(
            entity = FarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["farmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("farmId")],
)
data class KumeaNReceivedEntity(
    @PrimaryKey val id: String,
    val farmId: String,
    /**
     * Plain string, no catalogue FK — the catalogue table does not exist yet and
     * inventing one here is the KWAP-02 work this shim exists to avoid.
     */
    val strainCode: String,
    /** 50 | 100 | 150. The pack, in grams. 100 g is the forage pack. */
    val packSizeG: Int,
    /** `DDMMYY + GG + S` — e.g. `130826-01-S`. The reconciliation key. */
    val batchNumber: String,
    val qty: Int,
    /** UTC ISO-8601. When the farmer actually received it, not when it was typed. */
    val occurredAt: String,
    /**
     * WHO HANDED IT OVER. Derived from the signed-in caller's linked agent,
     * never selected — the same derive-don't-check rule as ward. NOT NULL: the
     * repository refuses to build a row without one, so the backfill into
     * `stock_distributions` can always attribute a distribution.
     */
    val recordedByAgentId: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)
