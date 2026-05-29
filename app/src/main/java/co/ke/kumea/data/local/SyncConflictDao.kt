package co.ke.kumea.data.local

import androidx.room.Dao
import androidx.room.Insert

@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(conflict: SyncConflictEntity)
}
