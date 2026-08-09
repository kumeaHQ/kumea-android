package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Biofix sale record (P1-T3) — the first money path in the distribution
 * layer. An Order belongs to a Farm: in Phase-1a terms "registering a farmer"
 * creates a Farm, so farmerId points at the farm row that IS the farmer record
 * (same ownership chain as Note, one level shallower).
 *
 * MONEY: unitPrice is integer CENTS as a **Long** (native Room INTEGER), never
 * Double/Float — the same discipline as NoteEntity.amountCents. On the wire it
 * travels as a String (see OrderCreateRequest/OrderResponse); the Long↔String
 * conversion happens only at the network boundary inside OrderRepository.
 * Derived line totals (qty × unitPrice) are computed ONLY via
 * Money.lineTotalCents (Long math, overflow-checked) — never Int arithmetic: at
 * KES 1,000/sachet a large dealer order crosses Int32 in cents.
 *
 * channel is REQUIRED (non-null) — commission rules differ per channel, so an
 * unchannelled sale is structurally impossible. Server enum values kept
 * verbatim as lowercase Strings (same convention as AgentEntity.role); the
 * valid set is OrderChannels.
 *
 * agentId is the COMMERCIAL attribution (who sold) — the selling Agent's STABLE
 * UUID (P1-T8). It can never resolve to an extension_officer (the server rejects
 * it: service guard + DB trigger). The device already holds this UUID because it
 * created the agent locally, so attribution is correct even before the agent's
 * code is server-canonicalised — the UUID is stable across the
 * provisional→canonical code transition. SOFT reference (no Room FK): the agent
 * may not be on this device.
 *
 * agentCode is a DISPLAY denormalization ONLY, not the attribution key — a
 * server-owned, reassignable label. The server re-derives the stored code from
 * agentId, so the device's provisional code is never the source of truth.
 *
 * pendingSync/syncAction drive offline-first sync (P1-T5): a sale is recorded
 * via OrderRepository.createLocal as a pending CREATE and pushed by SyncWorker,
 * which defers the order until its Farm (farmerId) and Agent (agentId) parents
 * have synced.
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = FarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["farmerId"],
            // CASCADE never fires during normal operation because soft delete is an
            // UPDATE, not a DELETE (same as Note → Field).
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("farmerId")],
)
data class OrderEntity(
    @PrimaryKey val id: String,
    val farmerId: String,
    // P1-T8: the selling agent's STABLE UUID — the authoritative attribution.
    val agentId: String?,
    // Display-only denormalization (server-derived from agentId); never the key.
    val agentCode: String?,
    val dealerId: String?,
    val sku: String,
    val qty: Int,
    val unitPrice: Long,
    val channel: String,
    val paymentStatus: String,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

/**
 * The server's OrderChannel enum, verbatim. A sale MUST carry one of these —
 * the create screen forces an explicit choice (no default).
 */
val OrderChannels = listOf("direct", "dealer", "agent", "ngo", "msimu")
