package com.steply.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.steply.app.data.local.entities.MovementHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovementHistoryDao {
    @Query("SELECT * FROM movement_history ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<MovementHistoryEntity>>

    @Query("SELECT * FROM movement_history WHERE profileId = :profileId ORDER BY receivedAt DESC")
    fun observeByProfileId(profileId: String): Flow<List<MovementHistoryEntity>>

    @Query("SELECT * FROM movement_history ORDER BY receivedAt DESC")
    suspend fun getAll(): List<MovementHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: MovementHistoryEntity)

    @Query("DELETE FROM movement_history")
    suspend fun deleteAll()
}
