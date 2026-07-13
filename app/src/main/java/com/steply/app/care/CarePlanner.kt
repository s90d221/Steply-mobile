package com.steply.app.care

import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId
import java.security.MessageDigest

data class CarePlannedDecision(
    val candidates: List<CareActionCandidate>,
    val evaluations: List<CareGuardrailEvaluation>,
    val candidateDecisions: List<CareCandidateDecision>,
    val selectedBranch: CareDecisionBranch,
    val selectedActions: List<CareActionCandidate>,
)

object CareStableIds {
    fun eventId(profileId: String, type: CareEventType, sourceEventId: String): String =
        stable("event", profileId, type.wireValue, sourceEventId)

    fun decisionId(eventId: String): String = stable("decision", eventId)

    fun causalActionKey(profileId: String, actionType: CareActionType, target: String, causalSignal: String): String =
        stable("causal-action", profileId, actionType.wireValue, target, causalSignal)

    private fun stable(vararg parts: String): String {
        val source = parts.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

class CarePlanner(
    private val config: CareAgentConfig = CareAgentConfigV1.value,
    private val guardrail: CareGuardrail = CareGuardrail(),
) {
    fun plan(event: CareEvent, state: CareInputState): CarePlannedDecision {
        require(event.profileId == state.profile.profileId) { "Care event profile does not match perceived state" }
        require(state.recentAssessments.size <= config.recentAssessmentLimit) { "Care state exceeds recent assessment limit" }
        require(state.invalidAttemptRatio.isFinite() && state.invalidAttemptRatio in 0.0..1.0) {
            "Invalid attempt ratio must be between zero and one"
        }
        require(state.invalidAttemptNumerator >= 0 && state.invalidAttemptDenominator >= 0)
        require(state.invalidAttemptNumerator <= state.invalidAttemptDenominator)
        val expectedInvalidRatio = if (state.invalidAttemptDenominator == 0) 0.0 else
            state.invalidAttemptNumerator.toDouble() / state.invalidAttemptDenominator.toDouble()
        require(kotlin.math.abs(state.invalidAttemptRatio - expectedInvalidRatio) < 1e-9) {
            "Invalid attempt ratio must preserve its numerator and denominator"
        }

        val candidates = buildList {
            if (state.safetyEvents.any { it.active }) addAll(safetyActions(event, state))
            if (state.fallReports.any { it.unresolved }) addAll(fallActions(event, state))
            if (requiresProfessionalEscalation(state)) addAll(escalationActions(event, state))
            if (state.trend.declining && state.trend.consecutiveDeclines >= config.decliningConsecutiveAssessments) {
                addAll(decliningActions(event, state))
            }
            if (state.perceivedAt >= state.reassessmentDueAt) add(dueReassessmentAction(event, state))
            if (hasLowAdherence(state)) addAll(lowAdherenceActions(event, state))
            if (state.progressionEligible) add(progressionAction(event, state))
            addAll(maintainActions(event, state))
        }
        val evaluations = candidates.map { guardrail.evaluate(it, state) }
        val allowed = candidates.filter { candidate ->
            evaluations.first { it.actionId == candidate.actionId }.allowed
        }
        val selectedPriority = allowed.minOfOrNull { it.branch.priority } ?: CareDecisionBranch.MAINTENANCE.priority
        val selected = allowed.filter { it.branch.priority == selectedPriority }
        val selectedBranch = selected.firstOrNull()?.branch ?: CareDecisionBranch.MAINTENANCE
        val decisions = candidates.map { candidate ->
            val evaluation = evaluations.first { it.actionId == candidate.actionId }
            when {
                !evaluation.allowed -> CareCandidateDecision(
                    candidate.actionId,
                    CareCandidateDisposition.REJECTED_BY_GUARDRAIL,
                    evaluation.checks.filterNot { it.passed }.joinToString("+") { it.reasonCode },
                )
                candidate in selected -> CareCandidateDecision(
                    candidate.actionId,
                    CareCandidateDisposition.SELECTED,
                    "HIGHEST_ALLOWED_PRIORITY",
                )
                else -> CareCandidateDecision(
                    candidate.actionId,
                    CareCandidateDisposition.NOT_SELECTED_LOWER_PRIORITY,
                    "LOWER_PRIORITY_THAN_${selectedBranch.wireValue.uppercase()}",
                )
            }
        }
        return CarePlannedDecision(candidates, evaluations, decisions, selectedBranch, selected)
    }

    private fun requiresProfessionalEscalation(state: CareInputState): Boolean {
        val clinical = state.canonicalClinicalReference
        return clinical.risk == SteadiRisk.HIGH ||
            VulnerabilityId.V6 in clinical.vulnerabilityIds ||
            VulnerabilityId.V7 in clinical.vulnerabilityIds
    }

    private fun hasLowAdherence(state: CareInputState): Boolean {
        val relevant = state.adherence.completedSessionsByWeek.takeLast(config.lowAdherenceConsecutiveWeeks)
        return relevant.size == config.lowAdherenceConsecutiveWeeks &&
            relevant.all { it <= config.lowAdherenceMaximumCompletedSessions }
    }

    private fun safetyActions(event: CareEvent, state: CareInputState): List<CareActionCandidate> {
        val causal = "safety:${state.safetyEvents.filter { it.active }.map { it.eventId }.sorted().joinToString(",")}" 
        return listOf(
            action(event, CareDecisionBranch.SAFETY_EVENT, CareActionType.STOP_SESSION, CareToolId.PROGRESS_STORE, "active_session", "SAFETY_EVENT_ACTIVE", causalSignal = causal),
            action(event, CareDecisionBranch.SAFETY_EVENT, CareActionType.REQUEST_IMMEDIATE_REASSESSMENT, CareToolId.SCHEDULER, "reassessment", "SAFETY_REASSESSMENT", scheduledAt = state.perceivedAt, causalSignal = causal),
            action(event, CareDecisionBranch.SAFETY_EVENT, CareActionType.RECOMMEND_MEDICAL_REVIEW, CareToolId.NOTIFIER, "profile", "SAFETY_MEDICAL_REVIEW", messageTemplateId = "care_safety_stop_and_review", recipientId = state.profile.profileId, causalSignal = causal),
        )
    }

    private fun fallActions(event: CareEvent, state: CareInputState): List<CareActionCandidate> {
        val causal = "fall:${state.fallReports.filter { it.unresolved }.map { it.eventId }.sorted().joinToString(",")}" 
        return listOf(
            action(event, CareDecisionBranch.FALL_REPORTED, CareActionType.REQUEST_IMMEDIATE_REASSESSMENT, CareToolId.SCHEDULER, "reassessment", "FALL_REPORTED", scheduledAt = state.perceivedAt, causalSignal = causal),
            action(event, CareDecisionBranch.FALL_REPORTED, CareActionType.RECOMMEND_MEDICAL_REVIEW, CareToolId.NOTIFIER, "profile", "FALL_MEDICAL_REVIEW", messageTemplateId = "care_fall_medical_review", recipientId = state.profile.profileId, causalSignal = causal),
        )
    }

    private fun escalationActions(event: CareEvent, state: CareInputState) = buildList {
        val causal = "assessment:${state.canonicalClinicalReference.assessmentSessionId}:${state.canonicalClinicalReference.assessmentRevision}"
        add(action(event, CareDecisionBranch.HIGH_OR_V6_V7, CareActionType.REQUIRE_PROFESSIONAL_REVIEW, CareToolId.NOTIFIER, "profile", "HIGH_OR_V6_V7", messageTemplateId = "care_professional_review_required", recipientId = state.profile.profileId, causalSignal = causal))
        add(action(event, CareDecisionBranch.HIGH_OR_V6_V7, CareActionType.HOLD_PROGRESSION, CareToolId.PROGRESS_STORE, "progression", "PROFESSIONAL_REVIEW_REQUIRED", causalSignal = causal))
        if (state.caregiverNotificationsConsented) {
            add(action(event, CareDecisionBranch.HIGH_OR_V6_V7, CareActionType.NOTIFY_CONSENTED_CAREGIVER, CareToolId.NOTIFIER, "caregiver", "ESCALATION_WITH_CONSENT", messageTemplateId = "care_caregiver_professional_review", recipientId = "consented_caregiver", causalSignal = causal))
        }
    }

    private fun decliningActions(event: CareEvent, state: CareInputState) = buildList {
        val causal = "decline:${state.canonicalClinicalReference.assessmentSessionId}:${state.canonicalClinicalReference.assessmentRevision}"
        add(action(event, CareDecisionBranch.DECLINING_TREND, CareActionType.ADVANCE_REASSESSMENT, CareToolId.SCHEDULER, "reassessment", "CONSECUTIVE_DECLINE", scheduledAt = state.perceivedAt, causalSignal = causal))
        add(action(event, CareDecisionBranch.DECLINING_TREND, CareActionType.HOLD_PROGRESSION, CareToolId.PROGRESS_STORE, "progression", "DECLINE_HOLDS_PROGRESSION", causalSignal = causal))
        if (state.caregiverNotificationsConsented) {
            add(action(event, CareDecisionBranch.DECLINING_TREND, CareActionType.NOTIFY_CONSENTED_CAREGIVER, CareToolId.NOTIFIER, "caregiver", "DECLINE_WITH_CONSENT", messageTemplateId = "care_caregiver_declining_trend", recipientId = "consented_caregiver", causalSignal = causal))
        }
    }

    private fun dueReassessmentAction(event: CareEvent, state: CareInputState) = action(
        event, CareDecisionBranch.REASSESSMENT_DUE, CareActionType.SCHEDULE_DUE_REASSESSMENT,
        CareToolId.SCHEDULER, "reassessment", "REASSESSMENT_DUE", scheduledAt = state.reassessmentDueAt,
        causalSignal = "due:${state.canonicalClinicalReference.assessmentSessionId}:${state.reassessmentDueAt}",
    )

    private fun lowAdherenceActions(event: CareEvent, state: CareInputState) = buildList {
        val causal = "adherence:${state.adherence.completedSessionsByWeek.joinToString(",")}:${state.canonicalClinicalReference.prescriptionPlanId}"
        add(action(event, CareDecisionBranch.LOW_ADHERENCE, CareActionType.ADJUST_REMINDER, CareToolId.SCHEDULER, "exercise_reminder", "LOW_ADHERENCE", scheduledAt = state.nextPlannedSessionAt ?: state.perceivedAt, causalSignal = causal))
        add(action(event, CareDecisionBranch.LOW_ADHERENCE, CareActionType.PROPOSE_SPLIT_SESSION, CareToolId.PROGRESS_STORE, "session_structure", "LOW_ADHERENCE_SPLIT_ALLOWED", causalSignal = causal))
        if (state.caregiverNotificationsConsented) {
            add(action(event, CareDecisionBranch.LOW_ADHERENCE, CareActionType.NOTIFY_CONSENTED_CAREGIVER, CareToolId.NOTIFIER, "caregiver", "LOW_ADHERENCE_WITH_CONSENT", messageTemplateId = "care_caregiver_low_adherence", recipientId = "consented_caregiver", causalSignal = causal))
        }
    }

    private fun progressionAction(event: CareEvent, state: CareInputState) = action(
        event, CareDecisionBranch.PROGRESSION_AVAILABLE, CareActionType.PROPOSE_PROGRESSION,
        CareToolId.NOTIFIER, "progression", "DETERMINISTIC_PROGRESSION_ELIGIBLE",
        messageTemplateId = "care_progression_approval_request", recipientId = state.profile.profileId,
        causalSignal = "progression:${state.canonicalClinicalReference.prescriptionPlanId}:${state.canonicalClinicalReference.assessmentRevision}",
    )

    private fun maintainActions(event: CareEvent, state: CareInputState) = buildList {
        add(action(event, CareDecisionBranch.MAINTENANCE, CareActionType.MAINTAIN_PLAN, CareToolId.PROGRESS_STORE, "care_plan", "NO_HIGHER_PRIORITY_SIGNAL"))
        state.nextPlannedSessionAt?.let { scheduledAt ->
            add(action(event, CareDecisionBranch.MAINTENANCE, CareActionType.SCHEDULE_SESSION, CareToolId.SCHEDULER, "exercise_session", "NEXT_PLANNED_SESSION", scheduledAt = scheduledAt))
        }
        if (event.type == CareEventType.SCHEDULED_WAKEUP) {
            add(
                action(
                    event, CareDecisionBranch.MAINTENANCE, CareActionType.COMPOSE_WEEKLY_REPORT,
                    CareToolId.REPORT_COMPOSER, "weekly_report", "SCHEDULED_WEEKLY_REPORT",
                    reportStart = state.perceivedAt - config.weeklyReportIntervalMs,
                    reportEnd = state.perceivedAt,
                    causalSignal = "weekly-report:${state.perceivedAt / config.weeklyReportIntervalMs}",
                ),
            )
        }
    }

    private fun action(
        event: CareEvent,
        branch: CareDecisionBranch,
        type: CareActionType,
        tool: CareToolId,
        target: String,
        reason: String,
        scheduledAt: Long? = null,
        messageTemplateId: String? = null,
        recipientId: String? = null,
        reportStart: Long? = null,
        reportEnd: Long? = null,
        causalSignal: String = event.eventId,
    ): CareActionCandidate {
        val key = CareStableIds.causalActionKey(event.profileId, type, target, causalSignal)
        return CareActionCandidate(
            actionId = key,
            idempotencyKey = key,
            eventId = event.eventId,
            profileId = event.profileId,
            branch = branch,
            actionType = type,
            toolId = tool,
            target = target,
            reasonCodes = listOf(reason),
            payload = CareActionPayload(
                scheduledAtMs = scheduledAt,
                messageTemplateId = messageTemplateId,
                recipientId = recipientId,
                reportPeriodStartMs = reportStart,
                reportPeriodEndMs = reportEnd,
            ),
        )
    }
}

class CareGuardrail {
    fun evaluate(candidate: CareActionCandidate, state: CareInputState): CareGuardrailEvaluation {
        val normalizedKeys = candidate.payload.parameters.keys.map(::normalizeKey).toSet()
        val hasSafety = state.safetyEvents.any { it.active }
        val needsEscalation = state.canonicalClinicalReference.risk == SteadiRisk.HIGH ||
            VulnerabilityId.V6 in state.canonicalClinicalReference.vulnerabilityIds ||
            VulnerabilityId.V7 in state.canonicalClinicalReference.vulnerabilityIds
        val checks = listOf(
            check("GR1", normalizedKeys.none { it in riskMutationKeys }, "RISK_MUTATION_FORBIDDEN"),
            check("GR2", normalizedKeys.none { it in cutoffMutationKeys }, "CLINICAL_CUTOFF_MUTATION_FORBIDDEN"),
            check("GR3", normalizedKeys.none { it in prescriptionMutationKeys }, "PRESCRIPTION_MUTATION_FORBIDDEN"),
            check(
                "GR4",
                candidate.actionType !in reassessmentActions ||
                    candidate.payload.scheduledAtMs?.let { it <= maxOf(state.reassessmentDueAt, state.perceivedAt) } == true,
                "REASSESSMENT_DELAY_FORBIDDEN",
            ),
            check("GR5", !hasSafety || candidate.branch == CareDecisionBranch.SAFETY_EVENT, "SAFETY_EVENT_HAS_PRIORITY"),
            check(
                "GR6",
                !needsEscalation || candidate.branch.priority <= CareDecisionBranch.HIGH_OR_V6_V7.priority,
                "ESCALATION_CANNOT_BE_WEAKENED",
            ),
            check(
                "GR7",
                candidate.toolId != CareToolId.NOTIFIER ||
                    (!candidate.payload.messageTemplateId.isNullOrBlank() && normalizedKeys.none { it in freeTextKeys }),
                "UNREVIEWED_MESSAGE_FORBIDDEN",
            ),
            check("GR8", candidate.actionId.isNotBlank() && candidate.idempotencyKey.isNotBlank(), "AUDIT_ID_REQUIRED"),
        )
        return CareGuardrailEvaluation(candidate.actionId, checks.all { it.passed }, checks)
    }

    private fun check(id: String, passed: Boolean, failure: String) =
        CareGuardrailCheck(id, passed, if (passed) "${id}_PASSED" else failure)

    private fun normalizeKey(key: String) = key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
        .replace("-", "_")
        .replace(".", "_")

    private companion object {
        val riskMutationKeys = setOf("risk", "risk_level", "steadi", "steadi_risk")
        val cutoffMutationKeys = setOf("cutoff", "threshold", "chair_cutoff", "tandem_cutoff", "clinical_rule")
        val prescriptionMutationKeys = setOf(
            "vulnerability", "vulnerability_ids", "prescription", "plan", "exercise_id", "level",
            "repetitions", "sets", "professional_approval", "progression_approval", "approval_status",
        )
        val freeTextKeys = setOf("message", "text", "body", "free_text")
        val reassessmentActions = setOf(
            CareActionType.REQUEST_IMMEDIATE_REASSESSMENT,
            CareActionType.ADVANCE_REASSESSMENT,
            CareActionType.SCHEDULE_DUE_REASSESSMENT,
        )
    }
}
