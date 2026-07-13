package com.steply.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.steply.app.data.local.entities.CareActionReceiptEntity
import com.steply.app.data.local.entities.CareAgentStateEntity
import com.steply.app.data.local.entities.CareDecisionLogEntity
import com.steply.app.data.local.entities.CareEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CareAgentDao {
    @Query("SELECT * FROM care_agent_states WHERE profileId = :profileId LIMIT 1")
    suspend fun getState(profileId: String): CareAgentStateEntity?

    @Query("SELECT * FROM care_agent_states WHERE profileId = :profileId LIMIT 1")
    fun observeState(profileId: String): Flow<CareAgentStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: CareAgentStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: CareEventEntity): Long

    @Query("SELECT * FROM care_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEvent(eventId: String): CareEventEntity?

    @Query("UPDATE care_events SET status = :status, updatedAt = :updatedAt WHERE eventId = :eventId")
    suspend fun updateEventStatus(eventId: String, status: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDecision(decision: CareDecisionLogEntity)

    @Query("SELECT * FROM care_decision_logs WHERE eventId = :eventId LIMIT 1")
    suspend fun getDecisionByEvent(eventId: String): CareDecisionLogEntity?

    @Query("SELECT * FROM care_decision_logs WHERE decisionId = :decisionId LIMIT 1")
    suspend fun getDecision(decisionId: String): CareDecisionLogEntity?

    @Query("SELECT * FROM care_decision_logs WHERE profileId = :profileId ORDER BY createdAt DESC")
    suspend fun getDecisions(profileId: String): List<CareDecisionLogEntity>

    @Query("SELECT * FROM care_decision_logs WHERE profileId = :profileId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestDecision(profileId: String): Flow<CareDecisionLogEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActionReceipt(receipt: CareActionReceiptEntity): Long

    @Update
    suspend fun updateActionReceipt(receipt: CareActionReceiptEntity)

    @Query("SELECT * FROM care_action_receipts WHERE actionId = :actionId LIMIT 1")
    suspend fun getActionReceipt(actionId: String): CareActionReceiptEntity?

    @Query("SELECT * FROM care_action_receipts WHERE decisionId = :decisionId ORDER BY createdAt ASC")
    suspend fun getActionReceipts(decisionId: String): List<CareActionReceiptEntity>

    @Transaction
    suspend fun persistDecisionPlan(
        decision: CareDecisionLogEntity,
        eventId: String,
        eventStatus: String,
        updatedAt: Long,
    ) {
        upsertDecision(decision)
        updateEventStatus(eventId, eventStatus, updatedAt)
    }

    @Transaction
    suspend fun persistDecisionCompletion(
        decision: CareDecisionLogEntity,
        state: CareAgentStateEntity,
        eventId: String,
        eventStatus: String,
        updatedAt: Long,
    ) {
        upsertDecision(decision)
        upsertState(state)
        updateEventStatus(eventId, eventStatus, updatedAt)
    }
}
