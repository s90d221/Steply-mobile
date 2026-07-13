package com.steply.app.domain.model

const val OTAGO_PRESCRIPTION_SCHEMA_VERSION = "otago_prescription.v1"
const val OTAGO_CATALOG_VERSION = "otago_catalog.v1"
const val EXERCISE_SESSION_RESULT_SCHEMA_VERSION = "exercise_session_result.v1"

enum class OtagoPlanStatus { BLOCKED, ACTIVE, PENDING_PROFESSIONAL_REVIEW }
enum class ExerciseCategory { WARMUP, STRENGTH, BALANCE, WALKING }
enum class ExerciseLevel { A, B, C, D }
enum class SupportRequirement { NONE, STABLE_SUPPORT, ONE_HAND, TWO_HAND, WALKING_AID }
enum class WeightMode { NONE, ANKLE_CUFF, FATIGUE_TARGET }
enum class CameraVerification { FULL, PARTIAL, MANUAL_ONLY }
enum class SupervisionRequirement { NONE, CAREGIVER_RECOMMENDED, PROFESSIONAL_REVIEW_REQUIRED }
enum class ApprovalStatus { NOT_REQUIRED, PENDING, APPROVED }
enum class ApprovalActorRole { USER, CAREGIVER, PROFESSIONAL }
enum class ProgressionStatus { PENDING_APPROVAL, APPROVED, APPLIED }
enum class ProgressionApprovalActor { USER, CAREGIVER_OR_RESPONSIBLE }
enum class ProgressionType { INCREASE_WEIGHT, REMOVE_SUPPORT, ADVANCE_VARIANT, INCREASE_SETS }
enum class ExerciseResultSource { LIVE_POSE, USER_CONFIRMED }

enum class ExerciseId {
    W1, W2, W3, W4, W5,
    S1, S2, S3, S4, S5,
    B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12,
    WALK,
}

data class ProfessionalApproval(
    val status: ApprovalStatus,
    val approvalId: String?,
    val approvedByRole: ApprovalActorRole?,
    val approvedAt: Long?,
)

data class ProgressionApproval(
    val actor: ProgressionApprovalActor,
    val approvedBy: String,
    val approvedAt: Long,
)

data class ProgressionProposal(
    val proposalId: String,
    val exerciseId: ExerciseId,
    val fromLevel: ExerciseLevel,
    val toLevel: ExerciseLevel,
    val fromVariantId: String,
    val toVariantId: String,
    val progressionType: ProgressionType,
    val weightIncrementMinKg: Double?,
    val weightIncrementMaxKg: Double?,
    val status: ProgressionStatus,
    val qualifyingSessionIds: List<String>,
    val approval: ProgressionApproval?,
)

data class PrescribedExercise(
    val exerciseId: ExerciseId,
    val category: ExerciseCategory,
    val level: ExerciseLevel,
    val variantId: String,
    val displayName: String,
    val repetitions: Int?,
    val sets: Int?,
    val repetitionsPerSide: Int?,
    val steps: Int?,
    val holdSeconds: Int?,
    val supportRequirement: SupportRequirement,
    val weightMode: WeightMode,
    val weightMinKg: Double?,
    val weightMaxKg: Double?,
    val tempoUpMinSeconds: Double?,
    val tempoUpMaxSeconds: Double?,
    val tempoDownMinSeconds: Double?,
    val tempoDownMaxSeconds: Double?,
    val breathingRule: String,
    val restMinSeconds: Int?,
    val restMaxSeconds: Int?,
    val cameraVerification: CameraVerification,
    val reasonVulnerabilityIds: List<VulnerabilityId>,
    val weakSideExtraSets: Int,
)

data class WalkingPlan(
    val exerciseId: ExerciseId,
    val category: ExerciseCategory,
    val targetMinutes: Int,
    val splitMinutes: List<Int>,
    val weeklyFrequency: Int,
    val pace: String,
    val requiresStrengthAndBalance: Boolean,
    val cameraVerification: CameraVerification,
)

data class OtagoPrescriptionPlan(
    val schemaVersion: String = OTAGO_PRESCRIPTION_SCHEMA_VERSION,
    val catalogVersion: String = OTAGO_CATALOG_VERSION,
    val planId: String,
    val userId: String,
    val riskLevel: SteadiRisk,
    val status: OtagoPlanStatus,
    val vulnerabilityIds: List<VulnerabilityId>,
    val warmups: List<PrescribedExercise>,
    val selectedExercises: List<PrescribedExercise>,
    val walkingPlan: WalkingPlan?,
    val professionalApproval: ProfessionalApproval,
    val supervisionRequirement: SupervisionRequirement,
    val caregiverRecommendedDays: Int,
    val requiresProfessionalReview: Boolean,
    val safetyNotices: List<String>,
    val progressionProposals: List<ProgressionProposal>,
    val sourceAssessmentIds: List<String>,
    val sourceResultIds: List<String>,
    val generatedByRuleVersion: String,
    val decisionTrace: List<String>,
)

data class ExerciseDosage(
    val repetitions: Int?,
    val sets: Int?,
    val repetitionsPerSide: Int?,
    val steps: Int?,
    val holdSeconds: Int?,
)

data class ExerciseSessionResult(
    val schemaVersion: String = EXERCISE_SESSION_RESULT_SCHEMA_VERSION,
    val resultId: String,
    val planId: String,
    val exerciseSessionId: String,
    val exerciseId: ExerciseId,
    val variantId: String,
    val level: ExerciseLevel,
    val source: ExerciseResultSource,
    val startedAt: Long,
    val completedAt: Long,
    val prescribedDosage: ExerciseDosage,
    val completedDosage: ExerciseDosage,
    val formAccurate: Boolean,
    val lowerBodyRecoveryWithoutGripping: Boolean?,
    val supportUsed: Boolean,
    val safetyEvents: List<String>,
    val cameraVerification: CameraVerification,
)

fun ExerciseId.expectedCategory(): ExerciseCategory = when (this) {
    ExerciseId.W1, ExerciseId.W2, ExerciseId.W3, ExerciseId.W4, ExerciseId.W5 -> ExerciseCategory.WARMUP
    ExerciseId.S1, ExerciseId.S2, ExerciseId.S3, ExerciseId.S4, ExerciseId.S5 -> ExerciseCategory.STRENGTH
    ExerciseId.B1, ExerciseId.B2, ExerciseId.B3, ExerciseId.B4, ExerciseId.B5, ExerciseId.B6,
    ExerciseId.B7, ExerciseId.B8, ExerciseId.B9, ExerciseId.B10, ExerciseId.B11, ExerciseId.B12 -> ExerciseCategory.BALANCE
    ExerciseId.WALK -> ExerciseCategory.WALKING
}

val OTAGO_WARMUP_IDS = listOf(ExerciseId.W1, ExerciseId.W2, ExerciseId.W3, ExerciseId.W4, ExerciseId.W5)
