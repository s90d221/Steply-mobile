package com.steply.app.care

import com.steply.app.data.repository.CareActionReservation
import com.steply.app.data.repository.CareAgentRepository
import com.steply.app.data.repository.CareEventClaim

fun interface CareClock {
    fun now(): Long
}

data class CareRunResult(
    val decisionId: String,
    val duplicateEvent: Boolean,
    val decision: CareDecision?,
)

class CareAgentRunner(
    private val repository: CareAgentRepository,
    private val tools: CareToolRegistry,
    private val planner: CarePlanner = CarePlanner(),
    private val clock: CareClock = CareClock(System::currentTimeMillis),
    private val perceptionSource: CarePerceptionSource? = null,
) {
    suspend fun run(event: CareEvent): CareRunResult {
        val perceivedAt = clock.now()
        val state = requireNotNull(perceptionSource) { "CarePerceptionSource is required for production runs" }
            .perceive(event, perceivedAt)
        return run(event, state)
    }

    internal suspend fun run(event: CareEvent, perceivedState: CareInputState): CareRunResult {
        val now = clock.now()
        val claim = repository.claimEvent(event, now)
        val decisionId = CareStableIds.decisionId(event.eventId)
        if (claim == CareEventClaim.ALREADY_PROCESSED) {
            return CareRunResult(
                decisionId = repository.decisionIdForEvent(event.eventId) ?: decisionId,
                duplicateEvent = true,
                decision = null,
            )
        }

        val priorState = repository.getState(event.profileId)
        if (claim == CareEventClaim.NEW && priorState != null) {
            require(
                perceivedState.canonicalClinicalReference.assessmentRevision >=
                    priorState.input.canonicalClinicalReference.assessmentRevision,
            ) { "Care agent cannot evaluate a stale canonical assessment revision" }
        }
        val observedState = if (claim == CareEventClaim.RESUMABLE) {
            repository.persistedObservedState(event.eventId) ?: perceivedState
        } else {
            perceivedState
        }
        val existingLog = repository.getDecisionLog(decisionId)
        val planned = planner.plan(event, observedState)
        var decision = CareDecision(
            decisionId = decisionId,
            eventId = event.eventId,
            profileId = event.profileId,
            observedState = observedState,
            candidates = planned.candidates,
            guardrailEvaluations = planned.evaluations,
            candidateDecisions = planned.candidateDecisions,
            selectedBranch = planned.selectedBranch,
            selectedActions = planned.selectedActions,
            executions = emptyList(),
            completedStages = listOf(
                CareLoopStage.PERCEIVE,
                CareLoopStage.EVALUATE,
                CareLoopStage.GENERATE_ACTIONS,
                CareLoopStage.GUARDRAIL,
                CareLoopStage.PRIORITIZE,
            ),
            status = CareDecisionStatus.EXECUTING,
            createdAt = existingLog?.createdAt ?: now,
            completedAt = null,
        )
        repository.persistPlan(decision, now)

        val executions = planned.selectedActions.map { candidate ->
            when (repository.reserveAction(decisionId, candidate, clock.now())) {
                CareActionReservation.ALREADY_SUCCEEDED -> CareActionExecution(
                    actionId = candidate.actionId,
                    toolId = candidate.toolId,
                    status = CareExecutionStatus.SKIPPED_DUPLICATE,
                    result = CareToolResult(true, "IDEMPOTENT_ACTION_ALREADY_SUCCEEDED"),
                )
                CareActionReservation.ALREADY_FINAL_FAILED -> CareActionExecution(
                    actionId = candidate.actionId,
                    toolId = candidate.toolId,
                    status = CareExecutionStatus.FAILED_FINAL,
                    result = CareToolResult(false, "IDEMPOTENT_ACTION_ALREADY_FAILED"),
                )
                CareActionReservation.NEW,
                CareActionReservation.RESUMABLE,
                -> execute(candidate)
            }
        }

        val hasRetryableFailure = executions.any { it.status == CareExecutionStatus.FAILED_RETRYABLE }
        val hasFinalFailure = executions.any { it.status == CareExecutionStatus.FAILED_FINAL }
        val completedAt = clock.now()
        val finalStatus = when {
            hasRetryableFailure || hasFinalFailure -> CareDecisionStatus.PARTIAL_FAILURE
            else -> CareDecisionStatus.COMPLETED
        }
        decision = decision.copy(
            executions = executions,
            completedStages = CareLoopStage.entries,
            status = finalStatus,
            completedAt = completedAt,
        )
        val eventStatus = when {
            hasRetryableFailure -> CareEventStatus.RETRY_PENDING
            hasFinalFailure -> CareEventStatus.FAILED_FINAL
            else -> CareEventStatus.PROCESSED
        }
        val currentState = repository.getState(event.profileId)
        val persistedState = CareAgentState(
            profileId = event.profileId,
            stateVersion = (currentState?.stateVersion ?: 0L) + 1L,
            input = observedState,
            latestDecisionId = decisionId,
            updatedAt = completedAt,
        )
        repository.persistCompletion(decision, persistedState, eventStatus, completedAt)
        return CareRunResult(decisionId, duplicateEvent = false, decision = decision)
    }

    private suspend fun execute(candidate: CareActionCandidate): CareActionExecution {
        repository.markActionRunning(candidate.actionId, clock.now())
        val result = try {
            tools.tool(candidate.toolId).execute(
                CareToolRequest(
                    actionId = candidate.actionId,
                    eventId = candidate.eventId,
                    profileId = candidate.profileId,
                    actionType = candidate.actionType,
                    payload = candidate.payload,
                ),
            )
        } catch (_: Throwable) {
            CareToolResult(
                success = false,
                resultCode = "TOOL_EXECUTION_FAILED_WITHOUT_CLINICAL_MUTATION",
                retryable = true,
            )
        }
        repository.completeAction(candidate.actionId, result, clock.now())
        val status = when {
            result.success -> CareExecutionStatus.SUCCEEDED
            result.retryable -> CareExecutionStatus.FAILED_RETRYABLE
            else -> CareExecutionStatus.FAILED_FINAL
        }
        return CareActionExecution(candidate.actionId, candidate.toolId, status, result)
    }
}
