package com.steply.app.data.repository

import com.steply.app.data.local.dao.WorkoutDao
import com.steply.app.data.local.entities.ExerciseCompletionEntity
import com.steply.app.data.local.entities.WorkoutSessionEntity
import com.steply.app.domain.model.ExerciseId
import com.steply.app.domain.model.WorkoutStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutRepositoryTest {
    @Test
    fun `REQ-S5-WORKOUT-01 multiple exercises share one session and completion is idempotent`() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutDao())
        val workout = repository.getOrCreateOpenWorkout(
            profileId = "profile-1",
            planId = "plan-1",
            prescribedExerciseIds = listOf(ExerciseId.W1, ExerciseId.S1),
            now = 100,
        )

        repository.completeExercise(workout.workoutSessionId, ExerciseId.W1, 110)
        repository.completeExercise(workout.workoutSessionId, ExerciseId.W1, 120)
        val completed = repository.completeExercise(workout.workoutSessionId, ExerciseId.S1, 130)

        assertEquals(workout.workoutSessionId, completed.workoutSessionId)
        assertEquals(setOf(ExerciseId.W1, ExerciseId.S1), completed.completedExerciseIds)
        assertEquals(WorkoutStatus.COMPLETED, completed.status)
        assertEquals(130L, completed.completedAt)
    }

    @Test
    fun `REQ-S5-WORKOUT-02 profile queries never mix another profile`() = runBlocking {
        val repository = WorkoutRepository(FakeWorkoutDao())
        repository.getOrCreateOpenWorkout("profile-a", "plan", listOf(ExerciseId.W1), 100)
        repository.getOrCreateOpenWorkout("profile-b", "plan", listOf(ExerciseId.W1), 200)

        val rows = repository.getByProfile("profile-a")

        assertEquals(1, rows.size)
        assertEquals("profile-a", rows.single().profileId)
    }
}

private class FakeWorkoutDao : WorkoutDao {
    private val sessions = linkedMapOf<String, WorkoutSessionEntity>()
    private val completions = linkedMapOf<String, ExerciseCompletionEntity>()
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<WorkoutSessionEntity?>>()
    private val completionFlows = mutableMapOf<String, MutableStateFlow<List<ExerciseCompletionEntity>>>()

    override suspend fun getOpen(profileId: String, planId: String): WorkoutSessionEntity? =
        sessions.values.lastOrNull { it.profileId == profileId && it.planId == planId && it.status == "IN_PROGRESS" }

    override suspend fun getSession(workoutSessionId: String) = sessions[workoutSessionId]
    override suspend fun getByProfile(profileId: String) = sessions.values.filter { it.profileId == profileId }
    override fun observeSession(workoutSessionId: String): Flow<WorkoutSessionEntity?> =
        sessionFlows.getOrPut(workoutSessionId) { MutableStateFlow(sessions[workoutSessionId]) }

    override suspend fun insertSession(session: WorkoutSessionEntity): Long {
        if (sessions.putIfAbsent(session.workoutSessionId, session) != null) return -1
        emitSession(session)
        return 1
    }

    override suspend fun markCompleted(workoutSessionId: String, completedAt: Long) {
        sessions[workoutSessionId]?.takeIf { it.status == "IN_PROGRESS" }?.let {
            emitSession(it.copy(status = "COMPLETED", completedAt = completedAt, updatedAt = completedAt))
        }
    }

    override suspend fun markInProgress(workoutSessionId: String, updatedAt: Long) {
        sessions[workoutSessionId]?.let {
            emitSession(it.copy(status = "IN_PROGRESS", completedAt = null, updatedAt = updatedAt))
        }
    }

    override suspend fun insertCompletion(completion: ExerciseCompletionEntity): Long {
        if (completions.putIfAbsent(completion.completionId, completion) != null) return -1
        emitCompletions(completion.workoutSessionId)
        return 1
    }

    override suspend fun getCompletion(completionId: String) = completions[completionId]

    override suspend fun deleteCompletion(workoutSessionId: String, exerciseId: String) {
        completions.entries.removeAll { it.value.workoutSessionId == workoutSessionId && it.value.exerciseId == exerciseId }
        emitCompletions(workoutSessionId)
    }

    override suspend fun getCompletions(workoutSessionId: String) =
        completions.values.filter { it.workoutSessionId == workoutSessionId }

    override fun observeCompletions(workoutSessionId: String): Flow<List<ExerciseCompletionEntity>> =
        completionFlows.getOrPut(workoutSessionId) { MutableStateFlow(completions.values.filter { it.workoutSessionId == workoutSessionId }) }

    private fun emitSession(session: WorkoutSessionEntity) {
        sessions[session.workoutSessionId] = session
        sessionFlows.getOrPut(session.workoutSessionId) { MutableStateFlow(null) }.value = session
    }

    private fun emitCompletions(workoutSessionId: String) {
        completionFlows.getOrPut(workoutSessionId) { MutableStateFlow(emptyList()) }.value =
            completions.values.filter { it.workoutSessionId == workoutSessionId }
    }
}
