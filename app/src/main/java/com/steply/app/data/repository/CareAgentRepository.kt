package com.steply.app.data.repository

import com.steply.app.care.CARE_AGENT_DECISION_SCHEMA_VERSION
import com.steply.app.care.CARE_AGENT_TOOL_RESULT_SCHEMA_VERSION
import com.steply.app.care.CARE_AGENT_STATE_SCHEMA_VERSION
import com.steply.app.care.CareActionCandidate
import com.steply.app.care.CareActionExecution
import com.steply.app.care.CareAgentState
import com.steply.app.care.CareDecision
import com.steply.app.care.CareDecisionStatus
import com.steply.app.care.CareDecisionBranch
import com.steply.app.care.CareDecisionSummary
import com.steply.app.care.CareEvent
import com.steply.app.care.CareEventStatus
import com.steply.app.care.CareExecutionStatus
import com.steply.app.care.CareInputState
import com.steply.app.care.CareStableIds
import com.steply.app.care.CareStateJsonCodec
import com.steply.app.care.CareToolResult
import com.steply.app.data.local.dao.CareAgentDao
import com.steply.app.data.local.entities.CareActionReceiptEntity
import com.steply.app.data.local.entities.CareAgentStateEntity
import com.steply.app.data.local.entities.CareDecisionLogEntity
import com.steply.app.data.local.entities.CareEventEntity
import java.security.MessageDigest
import org.json.JSONArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class CareEventClaim { NEW, RESUMABLE, ALREADY_PROCESSED }
enum class CareActionReservation { NEW, RESUMABLE, ALREADY_SUCCEEDED, ALREADY_FINAL_FAILED }

data class CareDecisionLogSnapshot(
    val decisionId: String,
    val eventId: String,
    val profileId: String,
    val selectedBranch: String,
    val status: CareDecisionStatus,
    val observedStateJson: String,
    val candidateActionsJson: String,
    val guardrailResultsJson: String,
    val candidateDecisionsJson: String,
    val executionResultsJson: String,
    val completedStagesJson: String,
    val createdAt: Long,
    val completedAt: Long?,
)

class CareAgentRepository(
    private val dao: CareAgentDao,
) {
    suspend fun getState(profileId: String): CareAgentState? = dao.getState(profileId)?.toDomain()

    fun observeState(profileId: String): Flow<CareAgentState?> =
        dao.observeState(profileId).map { it?.toDomain() }

    suspend fun claimEvent(event: CareEvent, now: Long): CareEventClaim {
        require(event.eventId == CareStableIds.eventId(event.profileId, event.type, event.sourceEventId)) {
            "Care event id must be derived from its immutable source identity"
        }
        require(event.schemaVersion == com.steply.app.care.CARE_AGENT_EVENT_SCHEMA_VERSION)
        val payloadJson = CareStateJsonCodec.encodeEventPayload(event.payload)
        val expected = CareEventEntity(
            eventId = event.eventId,
            schemaVersion = event.schemaVersion,
            profileId = event.profileId,
            eventType = event.type.wireValue,
            sourceEventId = event.sourceEventId,
            occurredAt = event.occurredAt,
            payloadHash = sha256(payloadJson),
            payloadJson = payloadJson,
            status = CareEventStatus.RECEIVED.name,
            createdAt = now,
            updatedAt = now,
        )
        if (dao.insertEvent(expected) != -1L) return CareEventClaim.NEW
        val existing = requireNotNull(dao.getEvent(event.eventId))
        require(existing.schemaVersion == expected.schemaVersion && existing.profileId == expected.profileId &&
            existing.eventType == expected.eventType &&
            existing.sourceEventId == expected.sourceEventId &&
            existing.occurredAt == expected.occurredAt &&
            existing.payloadHash == expected.payloadHash
        ) { "Care event id was reused with different content" }
        return if (existing.status == CareEventStatus.PROCESSED.name || existing.status == CareEventStatus.FAILED_FINAL.name) {
            CareEventClaim.ALREADY_PROCESSED
        } else {
            CareEventClaim.RESUMABLE
        }
    }

    suspend fun persistedObservedState(eventId: String): CareInputState? =
        dao.getDecisionByEvent(eventId)?.observedStateJson?.let(CareStateJsonCodec::decodeInput)

    suspend fun decisionIdForEvent(eventId: String): String? = dao.getDecisionByEvent(eventId)?.decisionId

    suspend fun getDecisionLog(decisionId: String): CareDecisionLogSnapshot? =
        dao.getDecision(decisionId)?.toSnapshot()

    suspend fun getDecisionLogs(profileId: String): List<CareDecisionLogSnapshot> =
        dao.getDecisions(profileId).map { it.toSnapshot() }

    fun observeLatestDecisionSummary(profileId: String): Flow<CareDecisionSummary?> =
        dao.observeLatestDecision(profileId).map { it?.toDecisionSummary() }

    private fun CareDecisionLogEntity.toDecisionSummary(): CareDecisionSummary {
        val selectedIds = JSONArray(candidateDecisionsJson).let { array ->
            buildSet {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    if (item.getString("disposition") == "SELECTED") add(item.getString("actionId"))
                }
            }
        }
        val selected = CareStateJsonCodec.decodeCandidates(candidateActionsJson)
            .filter { it.actionId in selectedIds }
        val branchWire = selectedBranch
        return CareDecisionSummary(
            decisionId = decisionId,
            selectedBranch = CareDecisionBranch.entries.first { it.wireValue == branchWire },
            selectedActions = selected,
            createdAt = createdAt,
        )
    }

    suspend fun hasReservedAction(actionId: String): Boolean = dao.getActionReceipt(actionId) != null

    suspend fun persistPlan(decision: CareDecision, now: Long) {
        require(decision.decisionId == CareStableIds.decisionId(decision.eventId))
        dao.persistDecisionPlan(
            decision = decision.toEntity(),
            eventId = decision.eventId,
            eventStatus = CareEventStatus.PROCESSING.name,
            updatedAt = now,
        )
    }

    suspend fun reserveAction(
        decisionId: String,
        candidate: CareActionCandidate,
        now: Long,
    ): CareActionReservation {
        require(candidate.actionId == candidate.idempotencyKey)
        require(candidate.schemaVersion == com.steply.app.care.CARE_AGENT_ACTION_SCHEMA_VERSION)
        val entity = CareActionReceiptEntity(
            actionId = candidate.actionId,
            actionSchemaVersion = candidate.schemaVersion,
            toolResultSchemaVersion = CARE_AGENT_TOOL_RESULT_SCHEMA_VERSION,
            idempotencyKey = candidate.idempotencyKey,
            decisionId = decisionId,
            eventId = candidate.eventId,
            profileId = candidate.profileId,
            actionType = candidate.actionType.wireValue,
            toolId = candidate.toolId.wireValue,
            status = CareExecutionStatus.PLANNED.name,
            requestJson = CareStateJsonCodec.encodeActionRequest(candidate),
            attemptResultsJson = "[]",
            resultCode = null,
            resultReference = null,
            retryable = false,
            createdAt = now,
            updatedAt = now,
        )
        if (dao.insertActionReceipt(entity) != -1L) return CareActionReservation.NEW
        val existing = requireNotNull(dao.getActionReceipt(candidate.actionId))
        require(existing.idempotencyKey == entity.idempotencyKey &&
            existing.profileId == entity.profileId &&
            existing.actionType == entity.actionType &&
            existing.toolId == entity.toolId
        ) { "Care action idempotency key was reused with different content" }
        return when (CareExecutionStatus.valueOf(existing.status)) {
            CareExecutionStatus.SUCCEEDED, CareExecutionStatus.SKIPPED_DUPLICATE -> CareActionReservation.ALREADY_SUCCEEDED
            CareExecutionStatus.FAILED_FINAL -> CareActionReservation.ALREADY_FINAL_FAILED
            else -> CareActionReservation.RESUMABLE
        }
    }

    suspend fun markActionRunning(actionId: String, now: Long) {
        val existing = requireNotNull(dao.getActionReceipt(actionId))
        dao.updateActionReceipt(existing.copy(status = CareExecutionStatus.RUNNING.name, updatedAt = now))
    }

    suspend fun completeAction(actionId: String, result: CareToolResult, now: Long) {
        val existing = requireNotNull(dao.getActionReceipt(actionId))
        val status = when {
            result.success -> CareExecutionStatus.SUCCEEDED
            result.retryable -> CareExecutionStatus.FAILED_RETRYABLE
            else -> CareExecutionStatus.FAILED_FINAL
        }
        val attempts = JSONArray(existing.attemptResultsJson)
            .put(CareStateJsonCodec.encodeToolResult(result, now))
        dao.updateActionReceipt(
            existing.copy(
                status = status.name,
                attemptResultsJson = attempts.toString(),
                resultCode = result.resultCode,
                resultReference = result.resultReference,
                retryable = result.retryable,
                updatedAt = now,
            ),
        )
    }

    suspend fun persistCompletion(
        decision: CareDecision,
        state: CareAgentState,
        eventStatus: CareEventStatus,
        now: Long,
    ) {
        validateState(state)
        require(state.latestDecisionId == decision.decisionId)
        require(state.input.canonicalClinicalReference == decision.observedState.canonicalClinicalReference) {
            "Care agent completion cannot alter canonical clinical references"
        }
        dao.persistDecisionCompletion(decision.toEntity(), state.toEntity(), decision.eventId, eventStatus.name, now)
    }

    private fun validateState(state: CareAgentState) {
        require(state.schemaVersion == CARE_AGENT_STATE_SCHEMA_VERSION)
        require(state.profileId == state.input.profile.profileId)
        require(state.input.canonicalClinicalReference.assessmentSessionId.isNotBlank())
        require(state.stateVersion >= 0L)
    }
}

private fun CareAgentState.toEntity(): CareAgentStateEntity {
    val clinical = input.canonicalClinicalReference
    return CareAgentStateEntity(
        profileId = profileId,
        schemaVersion = schemaVersion,
        stateVersion = stateVersion,
        assessmentSessionId = clinical.assessmentSessionId,
        assessmentRevision = clinical.assessmentRevision,
        prescriptionPlanId = clinical.prescriptionPlanId,
        professionalApprovalId = clinical.professionalApprovalId,
        latestDecisionId = latestDecisionId,
        inputStateJson = CareStateJsonCodec.encodeInput(input),
        updatedAt = updatedAt,
    )
}

private fun CareAgentStateEntity.toDomain(): CareAgentState {
    val input = CareStateJsonCodec.decodeInput(inputStateJson)
    require(schemaVersion == CARE_AGENT_STATE_SCHEMA_VERSION)
    require(profileId == input.profile.profileId)
    require(assessmentSessionId == input.canonicalClinicalReference.assessmentSessionId)
    require(assessmentRevision == input.canonicalClinicalReference.assessmentRevision)
    require(prescriptionPlanId == input.canonicalClinicalReference.prescriptionPlanId)
    require(professionalApprovalId == input.canonicalClinicalReference.professionalApprovalId)
    return CareAgentState(schemaVersion, profileId, stateVersion, input, latestDecisionId, updatedAt)
}

private fun CareDecision.toEntity() = CareDecisionLogEntity(
    decisionId = decisionId,
    schemaVersion = schemaVersion,
    eventId = eventId,
    profileId = profileId,
    selectedBranch = selectedBranch.wireValue,
    status = status.name,
    observedStateJson = CareStateJsonCodec.encodeInput(observedState),
    candidateActionsJson = CareStateJsonCodec.encodeCandidates(candidates),
    guardrailResultsJson = CareStateJsonCodec.encodeGuardrails(guardrailEvaluations),
    candidateDecisionsJson = CareStateJsonCodec.encodeCandidateDecisions(candidateDecisions),
    executionResultsJson = CareStateJsonCodec.encodeExecutions(executions),
    completedStagesJson = CareStateJsonCodec.encodeStages(completedStages),
    createdAt = createdAt,
    completedAt = completedAt,
)

private fun CareDecisionLogEntity.toSnapshot() = CareDecisionLogSnapshot(
    decisionId, eventId, profileId, selectedBranch, CareDecisionStatus.valueOf(status), observedStateJson,
    candidateActionsJson, guardrailResultsJson, candidateDecisionsJson, executionResultsJson,
    completedStagesJson, createdAt, completedAt,
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
