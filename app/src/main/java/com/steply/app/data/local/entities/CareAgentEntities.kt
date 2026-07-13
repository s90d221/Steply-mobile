package com.steply.app.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "care_agent_states")
data class CareAgentStateEntity(
    @PrimaryKey val profileId: String,
    val schemaVersion: String,
    val stateVersion: Long,
    val assessmentSessionId: String,
    val assessmentRevision: Long,
    val prescriptionPlanId: String?,
    val professionalApprovalId: String?,
    val latestDecisionId: String?,
    val inputStateJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "care_events",
    indices = [
        Index(value = ["profileId", "occurredAt"]),
        Index(value = ["payloadHash"]),
    ],
)
data class CareEventEntity(
    @PrimaryKey val eventId: String,
    val schemaVersion: String,
    val profileId: String,
    val eventType: String,
    val sourceEventId: String,
    val occurredAt: Long,
    val payloadHash: String,
    val payloadJson: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "care_decision_logs",
    indices = [Index(value = ["eventId"], unique = true), Index(value = ["profileId", "createdAt"])],
)
data class CareDecisionLogEntity(
    @PrimaryKey val decisionId: String,
    val schemaVersion: String,
    val eventId: String,
    val profileId: String,
    val selectedBranch: String,
    val status: String,
    val observedStateJson: String,
    val candidateActionsJson: String,
    val guardrailResultsJson: String,
    val candidateDecisionsJson: String,
    val executionResultsJson: String,
    val completedStagesJson: String,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "care_action_receipts",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["decisionId"]),
        Index(value = ["eventId"]),
    ],
)
data class CareActionReceiptEntity(
    @PrimaryKey val actionId: String,
    val actionSchemaVersion: String,
    val toolResultSchemaVersion: String,
    val idempotencyKey: String,
    val decisionId: String,
    val eventId: String,
    val profileId: String,
    val actionType: String,
    val toolId: String,
    val status: String,
    val requestJson: String,
    val attemptResultsJson: String,
    val resultCode: String?,
    val resultReference: String?,
    val retryable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
