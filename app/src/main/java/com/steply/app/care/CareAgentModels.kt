package com.steply.app.care

import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId

const val CARE_AGENT_STATE_SCHEMA_VERSION = "care_agent_state.v1"
const val CARE_AGENT_EVENT_SCHEMA_VERSION = "care_agent_event.v1"
const val CARE_AGENT_ACTION_SCHEMA_VERSION = "care_agent_action.v1"
const val CARE_AGENT_DECISION_SCHEMA_VERSION = "care_agent_decision.v1"
const val CARE_AGENT_TOOL_RESULT_SCHEMA_VERSION = "care_agent_tool_result.v1"
const val CARE_AGENT_PROJECTION_SCHEMA_VERSION = "care_agent_projection.v1"
const val CARE_AGENT_UPDATED_TYPE = "care-agent.updated"

enum class CareLoopStage {
    PERCEIVE,
    EVALUATE,
    GENERATE_ACTIONS,
    GUARDRAIL,
    PRIORITIZE,
    ACT,
    OBSERVE,
    PERSIST,
}

enum class CareDecisionBranch(val wireValue: String, val priority: Int) {
    SAFETY_EVENT("safety_event", 1),
    FALL_REPORTED("fall_reported", 2),
    HIGH_OR_V6_V7("high_or_v6_v7", 3),
    DECLINING_TREND("declining_trend", 4),
    REASSESSMENT_DUE("reassessment_due", 5),
    LOW_ADHERENCE("low_adherence", 6),
    PROGRESSION_AVAILABLE("progression_available", 7),
    MAINTENANCE("maintenance", 8),
}

enum class CareEventType(val wireValue: String) {
    SESSION_START("session_start"),
    SCHEDULED_WAKEUP("scheduled_wakeup"),
    SAFETY_EVENT("safety_event"),
    FALL_REPORTED("fall_reported"),
    ASSESSMENT_UPDATED("assessment_updated"),
    EXERCISE_RESULT_UPDATED("exercise_result_updated"),
    APPROVAL_UPDATED("approval_updated"),
    MANUAL_REFRESH("manual_refresh"),
}

enum class CareToolId(val wireValue: String) {
    SCHEDULER("scheduler"),
    NOTIFIER("notifier"),
    REPORT_COMPOSER("report_composer"),
    PROGRESS_STORE("progress_store"),
}

enum class CareActionType(val wireValue: String) {
    STOP_SESSION("stop_session"),
    REQUEST_IMMEDIATE_REASSESSMENT("request_immediate_reassessment"),
    RECOMMEND_MEDICAL_REVIEW("recommend_medical_review"),
    REQUIRE_PROFESSIONAL_REVIEW("require_professional_review"),
    HOLD_PROGRESSION("hold_progression"),
    ADVANCE_REASSESSMENT("advance_reassessment"),
    SCHEDULE_DUE_REASSESSMENT("schedule_due_reassessment"),
    SCHEDULE_SESSION("schedule_session"),
    ADJUST_REMINDER("adjust_reminder"),
    PROPOSE_SPLIT_SESSION("propose_split_session"),
    NOTIFY_CONSENTED_CAREGIVER("notify_consented_caregiver"),
    PROPOSE_PROGRESSION("propose_progression"),
    MAINTAIN_PLAN("maintain_plan"),
    COMPOSE_WEEKLY_REPORT("compose_weekly_report"),
}

enum class CareExecutionStatus {
    PLANNED,
    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SKIPPED_DUPLICATE,
}

enum class CareEventStatus { RECEIVED, PROCESSING, PROCESSED, RETRY_PENDING, FAILED_FINAL }
enum class CareDecisionStatus { PLANNED, EXECUTING, COMPLETED, PARTIAL_FAILURE, FAILED }
enum class CareCandidateDisposition { SELECTED, REJECTED_BY_GUARDRAIL, NOT_SELECTED_LOWER_PRIORITY }

data class CareCanonicalClinicalReference(
    val assessmentSessionId: String,
    val assessmentRevision: Long,
    val steadiRuleVersion: String,
    val risk: SteadiRisk,
    val vulnerabilityRuleVersion: String?,
    val vulnerabilityIds: Set<VulnerabilityId>,
    val prescriptionPlanId: String?,
    val prescriptionSchemaVersion: String?,
    val professionalApprovalStatus: ApprovalStatus,
    val professionalApprovalId: String?,
)

data class CareProfileSnapshot(
    val profileId: String,
    val birthYear: Int,
    val sex: String?,
    val sourceUpdatedAt: Long,
)

data class CareAssessmentSummary(
    val assessmentSessionId: String,
    val completedAt: Long,
    val chairStandRepetitions: Int,
    val tandemHoldSeconds: Double,
    val valid: Boolean,
)

data class CareTrendSnapshot(
    val declining: Boolean,
    val consecutiveDeclines: Int,
)

data class CareAdherenceSnapshot(
    val completedSessionsByWeek: List<Int>,
    val targetSessionsPerWeek: Int,
    val consecutiveLowWeeks: Int,
)

data class CareSafetyEventSnapshot(
    val eventId: String,
    val type: String,
    val occurredAt: Long,
    val active: Boolean,
)

data class CareFallReportSnapshot(
    val eventId: String,
    val occurredAt: Long,
    val injurious: Boolean,
    val unresolved: Boolean,
)

data class CareInputState(
    val profile: CareProfileSnapshot,
    val canonicalClinicalReference: CareCanonicalClinicalReference,
    val recentAssessments: List<CareAssessmentSummary>,
    val trend: CareTrendSnapshot,
    val adherence: CareAdherenceSnapshot,
    val safetyEvents: List<CareSafetyEventSnapshot>,
    val fallReports: List<CareFallReportSnapshot>,
    val invalidAttemptNumerator: Int,
    val invalidAttemptDenominator: Int,
    val invalidAttemptRatio: Double,
    val reassessmentDueAt: Long,
    val nextPlannedSessionAt: Long?,
    val progressionEligible: Boolean,
    val caregiverNotificationsConsented: Boolean,
    val perceivedAt: Long,
)

data class CareAgentState(
    val schemaVersion: String = CARE_AGENT_STATE_SCHEMA_VERSION,
    val profileId: String,
    val stateVersion: Long,
    val input: CareInputState,
    val latestDecisionId: String?,
    val updatedAt: Long,
)

data class CareEvent(
    val eventId: String,
    val profileId: String,
    val type: CareEventType,
    val sourceEventId: String,
    val occurredAt: Long,
    val payload: Map<String, String> = emptyMap(),
    val schemaVersion: String = CARE_AGENT_EVENT_SCHEMA_VERSION,
)

data class CareActionPayload(
    val scheduledAtMs: Long? = null,
    val messageTemplateId: String? = null,
    val recipientId: String? = null,
    val reportPeriodStartMs: Long? = null,
    val reportPeriodEndMs: Long? = null,
    val parameters: Map<String, String> = emptyMap(),
)

data class CareActionCandidate(
    val actionId: String,
    val idempotencyKey: String,
    val eventId: String,
    val profileId: String,
    val branch: CareDecisionBranch,
    val actionType: CareActionType,
    val toolId: CareToolId,
    val target: String,
    val reasonCodes: List<String>,
    val payload: CareActionPayload,
    val schemaVersion: String = CARE_AGENT_ACTION_SCHEMA_VERSION,
)

data class CareGuardrailCheck(
    val guardrailId: String,
    val passed: Boolean,
    val reasonCode: String,
)

data class CareGuardrailEvaluation(
    val actionId: String,
    val allowed: Boolean,
    val checks: List<CareGuardrailCheck>,
)

data class CareCandidateDecision(
    val actionId: String,
    val disposition: CareCandidateDisposition,
    val reasonCode: String,
)

data class CareToolRequest(
    val actionId: String,
    val eventId: String,
    val profileId: String,
    val actionType: CareActionType,
    val payload: CareActionPayload,
)

data class CareToolResult(
    val success: Boolean,
    val resultCode: String,
    val resultReference: String? = null,
    val retryable: Boolean = false,
)

data class CareActionExecution(
    val actionId: String,
    val toolId: CareToolId,
    val status: CareExecutionStatus,
    val result: CareToolResult?,
    val schemaVersion: String = CARE_AGENT_TOOL_RESULT_SCHEMA_VERSION,
)

data class CareDecisionSummary(
    val decisionId: String,
    val selectedBranch: CareDecisionBranch,
    val selectedActions: List<CareActionCandidate>,
    val createdAt: Long,
)

data class CareAgentProjection(
    val schemaVersion: String = CARE_AGENT_PROJECTION_SCHEMA_VERSION,
    val profileId: String,
    val stateVersion: Long,
    val currentSessionPlan: Map<String, String>?,
    val nextReassessmentAt: Long?,
    val latestDecision: CareDecisionSummary?,
    val updatedAt: Long,
)

data class CareAgentUpdate(
    val type: String = CARE_AGENT_UPDATED_TYPE,
    val schemaVersion: String = CARE_AGENT_STATE_SCHEMA_VERSION,
    val messageId: String,
    val profileId: String,
    val baseStateVersion: Long,
    val stateVersion: Long,
    val projection: CareAgentProjection,
)

data class CareDecision(
    val schemaVersion: String = CARE_AGENT_DECISION_SCHEMA_VERSION,
    val decisionId: String,
    val eventId: String,
    val profileId: String,
    val observedState: CareInputState,
    val candidates: List<CareActionCandidate>,
    val guardrailEvaluations: List<CareGuardrailEvaluation>,
    val candidateDecisions: List<CareCandidateDecision>,
    val selectedBranch: CareDecisionBranch,
    val selectedActions: List<CareActionCandidate>,
    val executions: List<CareActionExecution>,
    val completedStages: List<CareLoopStage>,
    val status: CareDecisionStatus,
    val createdAt: Long,
    val completedAt: Long?,
)
