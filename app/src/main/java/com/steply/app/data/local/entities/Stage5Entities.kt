package com.steply.app.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "assessment_summaries",
    indices = [Index(value = ["profileId", "completedAt"])],
)
data class AssessmentSummaryEntity(
    @PrimaryKey val assessmentSessionId: String,
    val schemaVersion: String,
    val profileId: String,
    val completedAt: Long,
    val risk: String,
    val vulnerabilityIdsJson: String,
    val chairStandRepetitions: Int,
    val sideBySideSeconds: Double,
    val semiTandemSeconds: Double,
    val tandemSeconds: Double,
    val oneLegSeconds: Double,
    val valid: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "workout_sessions",
    indices = [Index(value = ["profileId", "planId", "startedAt"])],
)
data class WorkoutSessionEntity(
    @PrimaryKey val workoutSessionId: String,
    val schemaVersion: String,
    val profileId: String,
    val planId: String,
    val prescribedExerciseIdsJson: String,
    val status: String,
    val startedAt: Long,
    val completedAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "exercise_completions",
    indices = [
        Index(value = ["workoutSessionId", "exerciseId"], unique = true),
        Index(value = ["profileId", "completedAt"]),
    ],
)
data class ExerciseCompletionEntity(
    @PrimaryKey val completionId: String,
    val schemaVersion: String,
    val workoutSessionId: String,
    val profileId: String,
    val planId: String,
    val exerciseId: String,
    val source: String,
    val completedAt: Long,
)

@Entity(
    tableName = "landmark_series",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["profileId", "assessmentSessionId"]),
        Index(value = ["attemptId"]),
        Index(value = ["resultId"]),
    ],
)
data class LandmarkSeriesEntity(
    @PrimaryKey val seriesId: String,
    val schemaVersion: String,
    val messageId: String,
    val profileId: String,
    val assessmentSessionId: String,
    val attemptId: String,
    val analysisSessionId: String,
    val resultId: String,
    val assessmentType: String,
    val status: String,
    val targetFps: Int,
    val startedAt: Long,
    val completedAt: Long,
    val sampleCount: Int,
    val samplesJson: String,
    val storedAt: Long,
)
