package com.steply.app.domain.model

const val ASSESSMENT_SESSION_SCHEMA_VERSION = "assessment_session.v2"
const val LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION = "assessment_session.v1"
const val ASSESSMENT_SESSION_UPDATED_TYPE = "assessment-session.updated"
const val STEADI_RULE_VERSION = "steadi_stage1.v1"
const val STAGE2_OPERATIONAL_CONFIG_VERSION = "stage2_operational.v1"
const val STAGE2_RESULT_SCHEMA_VERSION = "stage2_assessment_result.v1"
const val LEGACY_RESULT_SCHEMA_VERSION = "legacy_assessment_result.v1"
const val VULNERABILITY_RULE_VERSION = "stage2_vulnerability.v1"

enum class AssessmentSessionStatus { IN_PROGRESS, COMPLETED, CANCELLED }
enum class AssessmentSlotStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, NEEDS_RETRY }
enum class AssessmentAttemptStatus { IN_PROGRESS, PAUSED, VALID, INVALID, TRACKING_FAILED, CANCELLED, FAILED }
enum class AssessmentResultStatus { VALID, INVALID, TRACKING_FAILED }
enum class AssessmentType { FOUR_STAGE_BALANCE, CHAIR_STAND_30S }
enum class AssessmentSex { MALE, FEMALE }
enum class AssessmentFallCount { ZERO, ONE, TWO_OR_MORE }
enum class SteadiStatus { NOT_SCORABLE, SCORED }
enum class SteadiRisk { NOT_SCORABLE, LOW, MODERATE, HIGH }
enum class PrescriptionStatus { NOT_GENERATED, BLOCKED, ACTIVE, PENDING_PROFESSIONAL_REVIEW }
enum class QualityGateId { G1, G2, G3, G4, G5 }
enum class ChairStandFinalState { SIT, RISING, STAND, DESCENDING }
enum class ArmUseOutcome {
    NOT_DETECTED,
    RESTART_REQUIRED,
    DISQUALIFIED,
    NOT_MEASURABLE,
}
enum class BalanceStage { SIDE_BY_SIDE, SEMI_TANDEM, TANDEM, ONE_LEG }
enum class BalanceStageStatus { PASSED, FAILED, UNABLE_TO_ASSUME, NOT_ATTEMPTED, INVALID }
enum class BalanceFailureCode { F1, F2, F3, F4, F5 }
enum class VulnerabilityId { V1, V2, V3, V4, V5, V6, V7, V8, V9 }

data class AssessmentProfileSnapshot(
    val birthYear: Int?,
    val ageYears: Int?,
    val sex: AssessmentSex?,
)

data class AssessmentScreening(
    val status: AssessmentSlotStatus,
    val fallenPastYear: Boolean?,
    val feelsUnsteady: Boolean?,
    val worriedAboutFalling: Boolean?,
    val fallCount: AssessmentFallCount?,
    val injuriousFall: Boolean?,
)

data class NormalizedRoi(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class AssessmentOperationalContext(
    val operationalConfigVersion: String?,
    val supportRoiNormalized: NormalizedRoi?,
)

data class AssessmentCalibration(
    val sampledDurationMs: Long,
    val lFootM: Double,
    val hStandM: Double,
    val hSitM: Double?,
    val wShoulderM: Double,
    val dFoldM: Double?,
    val supportRoiNormalized: NormalizedRoi?,
)

data class QualityGateObservation(
    val gate: QualityGateId,
    val violationFrameCount: Int,
    val violationDurationMs: Long,
    val violationRatio: Double,
)

data class AssessmentQualitySummary(
    val gates: List<QualityGateObservation>,
    val g3ViolationRatio: Double,
    val invalidReasons: List<String>,
    val excludeFromTrends: Boolean,
)

data class ChairStandArmUse(
    val occurrenceCount: Int,
    val restartUsed: Boolean,
    val outcome: ArmUseOutcome,
)

data class ChairStandMeasurements(
    val observedRepetitions: Int,
    val completedRepetitions: Int,
    val cdcScoredRepetitions: Int,
    val finalRepetitionCredit: Int,
    val finalState: ChairStandFinalState,
    val armUse: ChairStandArmUse,
)

data class BalanceSwayMetrics(
    val mlRmsM: Double?,
    val apRmsM: Double?,
    val initialRmsM: Double?,
    val staticRmsM: Double?,
    val initialToStaticRatio: Double?,
    val mlToApRatio: Double?,
)

data class BalanceStageResult(
    val stage: BalanceStage,
    val onsetLatencyMs: Long?,
    val holdSeconds: Double,
    val status: BalanceStageStatus,
    val failureCode: BalanceFailureCode?,
    val failureReason: String?,
    val sway: BalanceSwayMetrics?,
)

data class BalanceMeasurements(
    val stages: List<BalanceStageResult>,
)

/**
 * Type-specific analyzer result shared by accepted results and terminal attempts.
 * New v2 results always include the operational evidence fields. They are nullable
 * only so persisted v1 snapshots can be decoded read-only without inventing data.
 */
data class AcceptedAssessmentResult(
    val resultSchemaVersion: String?,
    val resultId: String,
    val resultHash: String?,
    val attemptId: String,
    val analysisSessionId: String,
    val assessmentType: AssessmentType,
    val status: AssessmentResultStatus,
    val source: String,
    val completedAt: Long,
    val operationalConfigVersion: String?,
    val calibration: AssessmentCalibration?,
    val quality: AssessmentQualitySummary?,
    val vulnerabilityAssessment: VulnerabilityAssessment?,
    val legacyReadOnly: Boolean = false,
    val chairStand: ChairStandMeasurements? = null,
    val balance: BalanceMeasurements? = null,
    // v1-only values retained in memory for lossless legacy decoding.
    val legacyCompletedRepetitions: Int? = null,
    val legacyArmUseConfirmed: Boolean? = null,
    val legacyTandemHoldSeconds: Double? = null,
    val legacySideBySideHoldSeconds: Double? = null,
    val legacySemiTandemHoldSeconds: Double? = null,
    val legacyOneLegHoldSeconds: Double? = null,
) {
    val completedRepetitions: Int?
        get() = chairStand?.completedRepetitions ?: legacyCompletedRepetitions
    val armUseConfirmed: Boolean?
        get() = chairStand?.armUse?.outcome?.let { it == ArmUseOutcome.DISQUALIFIED }
            ?: legacyArmUseConfirmed
    val tandemHoldSeconds: Double?
        get() = balance?.stages?.firstOrNull { it.stage == BalanceStage.TANDEM }?.holdSeconds
            ?: legacyTandemHoldSeconds
    val sideBySideHoldSeconds: Double?
        get() = balance?.stages?.firstOrNull { it.stage == BalanceStage.SIDE_BY_SIDE }?.holdSeconds
            ?: legacySideBySideHoldSeconds
    val semiTandemHoldSeconds: Double?
        get() = balance?.stages?.firstOrNull { it.stage == BalanceStage.SEMI_TANDEM }?.holdSeconds
            ?: legacySemiTandemHoldSeconds
    val oneLegHoldSeconds: Double?
        get() = balance?.stages?.firstOrNull { it.stage == BalanceStage.ONE_LEG }?.holdSeconds
            ?: legacyOneLegHoldSeconds
}

data class AssessmentAttemptSummary(
    val attemptId: String,
    val analysisSessionId: String,
    val status: AssessmentAttemptStatus,
    val startedAt: Long,
    val completedAt: Long?,
    val supersedesAttemptId: String?,
    val resultHash: String? = null,
    val result: AcceptedAssessmentResult? = null,
)

data class AssessmentTestSlot(
    val status: AssessmentSlotStatus,
    val acceptedAttemptId: String?,
    val acceptedResult: AcceptedAssessmentResult?,
    val attempts: List<AssessmentAttemptSummary>,
)

data class AssessmentFunctionalTests(
    val fourStageBalance: AssessmentTestSlot,
    val chairStand30s: AssessmentTestSlot,
)

data class VulnerabilityEvidence(
    val vulnerabilityId: VulnerabilityId,
    val sourceResultId: String?,
    /** Canonical measurements object serialized verbatim. */
    val measurementsJson: String,
)

data class VulnerabilityAssessment(
    val activeIds: List<VulnerabilityId>,
    val evidence: List<VulnerabilityEvidence>,
    val ruleVersion: String = VULNERABILITY_RULE_VERSION,
)

data class SteadiScore(
    val status: SteadiStatus,
    val risk: SteadiRisk,
    val strengthProblem: Boolean?,
    val balanceProblem: Boolean?,
    val step1AtRisk: Boolean?,
    val step2Problem: Boolean?,
    val reasonCodes: List<String>,
    val ruleVersion: String = STEADI_RULE_VERSION,
)

data class AssessmentPrescription(
    val status: PrescriptionStatus,
    val plan: OtagoPrescriptionPlan?,
    val sessionResults: List<ExerciseSessionResult> = emptyList(),
)

data class AssessmentSession(
    val schemaVersion: String = ASSESSMENT_SESSION_SCHEMA_VERSION,
    val assessmentSessionId: String,
    val connectionSessionId: String?,
    val profileId: String,
    val revision: Long,
    val status: AssessmentSessionStatus,
    val screening: AssessmentScreening,
    val profileSnapshot: AssessmentProfileSnapshot,
    val operationalContext: AssessmentOperationalContext?,
    val functionalTests: AssessmentFunctionalTests,
    val vulnerabilityAssessment: VulnerabilityAssessment?,
    val steadi: SteadiScore,
    val exercisePrescription: AssessmentPrescription,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

data class AssessmentSessionEnvelope(
    val messageId: String,
    val baseRevision: Long,
    val session: AssessmentSession,
) {
    val revision: Long get() = session.revision
}

fun AssessmentSession.hasScoredAggregate(): Boolean {
    return status == AssessmentSessionStatus.COMPLETED &&
        screening.status == AssessmentSlotStatus.COMPLETED &&
        functionalTests.fourStageBalance.status == AssessmentSlotStatus.COMPLETED &&
        functionalTests.chairStand30s.status == AssessmentSlotStatus.COMPLETED &&
        steadi.status == SteadiStatus.SCORED &&
        steadi.risk != SteadiRisk.NOT_SCORABLE
}
