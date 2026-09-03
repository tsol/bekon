package pro.potoki.bekon.phone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentsDao {
    @Query("SELECT * FROM recent_calls ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RecentCall>>

    @Query("SELECT * FROM recent_calls WHERE id = :id")
    suspend fun get(id: Long): RecentCall?

    @Insert
    suspend fun insert(row: RecentCall): Long

    @Update
    suspend fun update(row: RecentCall)
}
