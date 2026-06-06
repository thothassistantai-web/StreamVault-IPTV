package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamvault.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun getAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getByIdOnce(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING')")
    fun getActive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'PAUSED') ORDER BY created_at ASC")
    suspend fun getQueuedOnce(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'PAUSED' AND retry_count < :maxRetries ORDER BY created_at ASC")
    suspend fun getRetryablePausedOnce(maxRetries: Int): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
