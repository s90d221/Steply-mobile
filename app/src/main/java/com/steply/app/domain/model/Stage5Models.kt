package com.steply.app.domain.model

const val ASSESSMENT_SUMMARY_SCHEMA_VERSION = "assessment_summary.v1"
const val WORKOUT_SESSION_SCHEMA_VERSION = "workout_session.v1"
const val EXERCISE_COMPLETION_SCHEMA_VERSION = "exercise_completion.v1"

data class AssessmentSummary(
    val assessmentSessionId: String,
    val profileId: String,
    val completedAt: Long,
    val risk: SteadiRisk,
    val vulnerabilityIds: List<VulnerabilityId>,
    val chairStandRepetitions: Int,
    val balanceSecondsByStage: Map<BalanceStage, Double>,
    val valid: Boolean,
)

enum class WorkoutStatus { IN_PROGRESS, COMPLETED }

data class WorkoutProgress(
    val workoutSessionId: String,
    val profileId: String,
    val planId: String,
    val prescribedExerciseIds: List<ExerciseId>,
    val completedExerciseIds: Set<ExerciseId>,
    val status: WorkoutStatus,
    val startedAt: Long,
    val completedAt: Long?,
)
