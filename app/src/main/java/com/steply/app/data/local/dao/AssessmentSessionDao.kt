package com.steply.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.steply.app.data.local.entities.AssessmentSessionEntity
import com.steply.app.data.local.entities.AssessmentMessageReceiptEntity
import com.steply.app.data.local.entities.AssessmentResultReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentSessionDao {
    @Query("SELECT * FROM assessment_sessions WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    fun observeActive(): Flow<AssessmentSessionEntity?>

    @Query("SELECT * FROM assessment_sessions WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActive(): AssessmentSessionEntity?

    @Query("SELECT * FROM assessment_sessions WHERE assessmentSessionId = :assessmentSessionId LIMIT 1")
    suspend fun getById(assessmentSessionId: String): AssessmentSessionEntity?

    @Query("SELECT * FROM assessment_sessions WHERE profileId = :profileId ORDER BY updatedAt DESC")
    suspend fun getByProfileId(profileId: String): List<AssessmentSessionEntity>

    @Query("SELECT * FROM assessment_sessions WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observeByProfileId(profileId: String): Flow<List<AssessmentSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AssessmentSessionEntity)

    @Query("UPDATE assessment_sessions SET isActive = 0 WHERE assessmentSessionId != :assessmentSessionId")
    suspend fun deactivateOthers(assessmentSessionId: String)

    @Query("UPDATE assessment_sessions SET isActive = 0 WHERE assessmentSessionId = :assessmentSessionId")
    suspend fun deactivate(assessmentSessionId: String)

    @Query("SELECT * FROM assessment_message_receipts WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageReceipt(messageId: String): AssessmentMessageReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageReceipt(receipt: AssessmentMessageReceiptEntity): Long

    @Query("SELECT * FROM assessment_result_receipts WHERE resultId = :resultId LIMIT 1")
    suspend fun getResultReceipt(resultId: String): AssessmentResultReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResultReceipt(receipt: AssessmentResultReceiptEntity): Long
}
