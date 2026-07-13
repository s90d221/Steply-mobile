package com.steply.app.data.repository

import com.steply.app.data.local.dao.AssessmentSummaryDao
import com.steply.app.data.local.dao.ProfileDataDao
import com.steply.app.data.local.dao.WorkoutDao
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.data.local.entities.ExerciseCompletionEntity
import com.steply.app.data.local.entities.WorkoutSessionEntity
import com.steply.app.domain.model.ASSESSMENT_SUMMARY_SCHEMA_VERSION
import com.steply.app.domain.model.AssessmentSummary
import com.steply.app.domain.model.BalanceStage
import com.steply.app.domain.model.EXERCISE_COMPLETION_SCHEMA_VERSION
import com.steply.app.domain.model.ExerciseId
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId
import com.steply.app.domain.model.WORKOUT_SESSION_SCHEMA_VERSION
import com.steply.app.domain.model.WorkoutProgress
import com.steply.app.domain.model.WorkoutStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

class AssessmentSummaryRepository(private val dao: AssessmentSummaryDao) {
    fun observeValidByProfile(profileId: String): Flow<List<AssessmentSummary>> =
        dao.observeValidByProfile(profileId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getValidByProfile(profileId: String): List<AssessmentSummary> =
        dao.getValidByProfile(profileId).map { it.toDomain() }
}

class WorkoutRepository(private val dao: WorkoutDao) {
    private val mutex = Mutex()

    suspend fun getOrCreateOpenWorkout(
        profileId: String,
        planId: String,
        prescribedExerciseIds: List<ExerciseId>,
        now: Long = System.currentTimeMillis(),
    ): WorkoutProgress = mutex.withLock {
        require(profileId.isNotBlank() && planId.isNotBlank() && prescribedExerciseIds.isNotEmpty())
        require(prescribedExerciseIds.distinct().size == prescribedExerciseIds.size)
        dao.getOpen(profileId, planId)?.let { existing ->
            require(existing.prescribedExerciseIdsJson.toExerciseIds() == prescribedExerciseIds) {
                "An open workout cannot change its prescribed exercises"
            }
            return@withLock existing.toProgress(dao.getCompletions(existing.workoutSessionId))
        }
        val row = WorkoutSessionEntity(
            workoutSessionId = UUID.randomUUID().toString(),
            schemaVersion = WORKOUT_SESSION_SCHEMA_VERSION,
            profileId = profileId,
            planId = planId,
            prescribedExerciseIdsJson = JSONArray(prescribedExerciseIds.map { it.name }).toString(),
            status = WorkoutStatus.IN_PROGRESS.name,
            startedAt = now,
            completedAt = null,
            updatedAt = now,
        )
        check(dao.insertSession(row) != -1L)
        row.toProgress(emptyList())
    }

    fun observeWorkout(workoutSessionId: String): Flow<WorkoutProgress?> = combine(
        dao.observeSession(workoutSessionId),
        dao.observeCompletions(workoutSessionId),
    ) { session, completions -> session?.toProgress(completions) }

    suspend fun completeExercise(
        workoutSessionId: String,
        exerciseId: ExerciseId,
        completedAt: Long = System.currentTimeMillis(),
    ): WorkoutProgress = mutex.withLock {
        val session = requireNotNull(dao.getSession(workoutSessionId)) { "Unknown workoutSessionId" }
        val prescribed = session.prescribedExerciseIdsJson.toExerciseIds()
        require(exerciseId in prescribed) { "Exercise is not prescribed for this workout" }
        val completion = ExerciseCompletionEntity(
            completionId = "$workoutSessionId:${exerciseId.name}",
            schemaVersion = EXERCISE_COMPLETION_SCHEMA_VERSION,
            workoutSessionId = workoutSessionId,
            profileId = session.profileId,
            planId = session.planId,
            exerciseId = exerciseId.name,
            source = "USER_CONFIRMED",
            completedAt = completedAt,
        )
        if (dao.insertCompletion(completion) == -1L) {
            val existing = requireNotNull(dao.getCompletion(completion.completionId))
            require(existing.workoutSessionId == completion.workoutSessionId &&
                existing.profileId == completion.profileId && existing.planId == completion.planId &&
                existing.exerciseId == completion.exerciseId
            ) { "Exercise completion id was reused with different content" }
        }
        val completions = dao.getCompletions(workoutSessionId)
        if (completions.map { it.exerciseId }.toSet().containsAll(prescribed.map { it.name })) {
            dao.markCompleted(workoutSessionId, completedAt)
        }
        requireNotNull(dao.getSession(workoutSessionId)).toProgress(completions)
    }

    suspend fun setExerciseCompleted(
        workoutSessionId: String,
        exerciseId: ExerciseId,
        completed: Boolean,
        changedAt: Long = System.currentTimeMillis(),
    ): WorkoutProgress {
        if (completed) return completeExercise(workoutSessionId, exerciseId, changedAt)
        return mutex.withLock {
            val session = requireNotNull(dao.getSession(workoutSessionId)) { "Unknown workoutSessionId" }
            require(exerciseId in session.prescribedExerciseIdsJson.toExerciseIds())
            dao.deleteCompletion(workoutSessionId, exerciseId.name)
            dao.markInProgress(workoutSessionId, changedAt)
            requireNotNull(dao.getSession(workoutSessionId)).toProgress(dao.getCompletions(workoutSessionId))
        }
    }

    suspend fun getByProfile(profileId: String): List<WorkoutProgress> =
        dao.getByProfile(profileId).map { it.toProgress(dao.getCompletions(it.workoutSessionId)) }
}

class ProfileDataRepository(private val dao: ProfileDataDao) {
    suspend fun purgeProfile(profileId: String) {
        require(profileId.isNotBlank())
        dao.purgeProfile(profileId)
    }
}

private fun AssessmentSummaryEntity.toDomain(): AssessmentSummary {
    require(schemaVersion == ASSESSMENT_SUMMARY_SCHEMA_VERSION)
    return AssessmentSummary(
        assessmentSessionId = assessmentSessionId,
        profileId = profileId,
        completedAt = completedAt,
        risk = SteadiRisk.valueOf(risk),
        vulnerabilityIds = vulnerabilityIdsJson.toVulnerabilityIds(),
        chairStandRepetitions = chairStandRepetitions,
        balanceSecondsByStage = linkedMapOf(
            BalanceStage.SIDE_BY_SIDE to sideBySideSeconds,
            BalanceStage.SEMI_TANDEM to semiTandemSeconds,
            BalanceStage.TANDEM to tandemSeconds,
            BalanceStage.ONE_LEG to oneLegSeconds,
        ),
        valid = valid,
    )
}

private fun WorkoutSessionEntity.toProgress(completions: List<ExerciseCompletionEntity>): WorkoutProgress {
    require(schemaVersion == WORKOUT_SESSION_SCHEMA_VERSION)
    return WorkoutProgress(
        workoutSessionId = workoutSessionId,
        profileId = profileId,
        planId = planId,
        prescribedExerciseIds = prescribedExerciseIdsJson.toExerciseIds(),
        completedExerciseIds = completions.map { ExerciseId.valueOf(it.exerciseId) }.toSet(),
        status = WorkoutStatus.valueOf(status),
        startedAt = startedAt,
        completedAt = completedAt,
    )
}

private fun String.toExerciseIds(): List<ExerciseId> = JSONArray(this).enumList()
private fun String.toVulnerabilityIds(): List<VulnerabilityId> = JSONArray(this).enumList()

private inline fun <reified T : Enum<T>> JSONArray.enumList(): List<T> = buildList {
    for (index in 0 until length()) add(enumValueOf(getString(index)))
}
