package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Order DAO — the same pendingSync / latest-updatedAt / markSynced contract as
 * NoteDao, one level shallower (an Order attaches directly to a Farm). Orders
 * are listed by the client-entered sale date, newest first.
 */
@Dao
interface OrderDao {
    /** All active (non-deleted) orders, newest sale first. */
    @Query("SELECT * FROM orders WHERE deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY date DESC")
    fun getAllActive(): Flow<List<OrderEntity>>

    /** Active orders for a single farmer (farm). */
    @Query("SELECT * FROM orders WHERE farmerId = :farmerId AND deletedAt IS NULL AND syncAction != 'DELETE' ORDER BY date DESC")
    fun getActiveByFarmer(farmerId: String): Flow<List<OrderEntity>>

    /**
     * Rows with pending local changes that need pushing. Same invariant as
     * NoteDao: pullSince() skips pending rows so push gets its turn first.
     */
    @Query("SELECT * FROM orders WHERE pendingSync = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<OrderEntity>

    @Query("SELECT MAX(updatedAt) FROM orders")
    suspend fun getLatestUpdatedAt(): String?

    @Upsert
    suspend fun upsertAll(orders: List<OrderEntity>)

    @Upsert
    suspend fun upsert(order: OrderEntity)

    /** Marks a row synced after a CREATE/UPDATE push (flips CREATE → UPDATE). */
    @Query("UPDATE orders SET pendingSync = 0, syncAction = 'UPDATE', updatedAt = :serverUpdatedAt WHERE id = :orderId")
    suspend fun markSynced(orderId: String, serverUpdatedAt: String)

    /** Marks a soft-delete row synced after a DELETE push succeeds. */
    @Query("UPDATE orders SET pendingSync = 0, deletedAt = :deletedAt WHERE id = :orderId")
    suspend fun markSyncedDelete(orderId: String, deletedAt: String)
}
