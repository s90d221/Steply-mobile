package com.steply.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.data.local.entities.ExerciseCompletionEntity
import com.steply.app.data.local.entities.LandmarkSeriesEntity
import com.steply.app.data.local.entities.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: AssessmentSummaryEntity)

    @Query("SELECT * FROM assessment_summaries WHERE profileId = :profileId AND valid = 1 ORDER BY completedAt ASC")
    fun observeValidByProfile(profileId: String): Flow<List<AssessmentSummaryEntity>>

    @Query("SELECT * FROM assessment_summaries WHERE profileId = :profileId AND valid = 1 ORDER BY completedAt ASC")
    suspend fun getValidByProfile(profileId: String): List<AssessmentSummaryEntity>
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_sessions WHERE profileId = :profileId AND planId = :planId AND status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpen(profileId: String, planId: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE workoutSessionId = :workoutSessionId LIMIT 1")
    suspend fun getSession(workoutSessionId: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE profileId = :profileId ORDER BY startedAt ASC")
    suspend fun getByProfile(profileId: String): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE workoutSessionId = :workoutSessionId LIMIT 1")
    fun observeSession(workoutSessionId: String): Flow<WorkoutSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Query("UPDATE workout_sessions SET status = 'COMPLETED', completedAt = :completedAt, updatedAt = :completedAt WHERE workoutSessionId = :workoutSessionId AND status = 'IN_PROGRESS'")
    suspend fun markCompleted(workoutSessionId: String, completedAt: Long)

    @Query("UPDATE workout_sessions SET status = 'IN_PROGRESS', completedAt = NULL, updatedAt = :updatedAt WHERE workoutSessionId = :workoutSessionId")
    suspend fun markInProgress(workoutSessionId: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletion(completion: ExerciseCompletionEntity): Long

    @Query("SELECT * FROM exercise_completions WHERE completionId = :completionId LIMIT 1")
    suspend fun getCompletion(completionId: String): ExerciseCompletionEntity?

    @Query("DELETE FROM exercise_completions WHERE workoutSessionId = :workoutSessionId AND exerciseId = :exerciseId")
    suspend fun deleteCompletion(workoutSessionId: String, exerciseId: String)

    @Query("SELECT * FROM exercise_completions WHERE workoutSessionId = :workoutSessionId ORDER BY completedAt ASC")
    suspend fun getCompletions(workoutSessionId: String): List<ExerciseCompletionEntity>

    @Query("SELECT * FROM exercise_completions WHERE workoutSessionId = :workoutSessionId ORDER BY completedAt ASC")
    fun observeCompletions(workoutSessionId: String): Flow<List<ExerciseCompletionEntity>>

}

@Dao
interface LandmarkSeriesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(series: LandmarkSeriesEntity): Long

    @Query("SELECT * FROM landmark_series WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getBySeriesId(seriesId: String): LandmarkSeriesEntity?

    @Query("SELECT * FROM landmark_series WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): LandmarkSeriesEntity?
}

@Dao
interface ProfileDataDao {
    @Query("DELETE FROM assessment_message_receipts WHERE assessmentSessionId IN (SELECT assessmentSessionId FROM assessment_sessions WHERE profileId = :profileId)")
    suspend fun deleteAssessmentMessages(profileId: String)

    @Query("DELETE FROM assessment_result_receipts WHERE assessmentSessionId IN (SELECT assessmentSessionId FROM assessment_sessions WHERE profileId = :profileId)")
    suspend fun deleteAssessmentResults(profileId: String)

    @Query("DELETE FROM assessment_summaries WHERE profileId = :profileId")
    suspend fun deleteAssessmentSummaries(profileId: String)

    @Query("DELETE FROM assessment_sessions WHERE profileId = :profileId")
    suspend fun deleteAssessmentSessions(profileId: String)

    @Query("DELETE FROM care_action_receipts WHERE profileId = :profileId")
    suspend fun deleteCareActionReceipts(profileId: String)

    @Query("DELETE FROM care_decision_logs WHERE profileId = :profileId")
    suspend fun deleteCareDecisions(profileId: String)

    @Query("DELETE FROM care_events WHERE profileId = :profileId")
    suspend fun deleteCareEvents(profileId: String)

    @Query("DELETE FROM care_agent_states WHERE profileId = :profileId")
    suspend fun deleteCareState(profileId: String)

    @Query("DELETE FROM exercise_completions WHERE profileId = :profileId")
    suspend fun deleteExerciseCompletions(profileId: String)

    @Query("DELETE FROM workout_sessions WHERE profileId = :profileId")
    suspend fun deleteWorkoutSessions(profileId: String)

    @Query("DELETE FROM landmark_series WHERE profileId = :profileId")
    suspend fun deleteLandmarkSeries(profileId: String)

    @Query("DELETE FROM user_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Transaction
    suspend fun purgeProfile(profileId: String) {
        deleteAssessmentMessages(profileId)
        deleteAssessmentResults(profileId)
        deleteAssessmentSummaries(profileId)
        deleteCareActionReceipts(profileId)
        deleteCareDecisions(profileId)
        deleteCareEvents(profileId)
        deleteCareState(profileId)
        deleteExerciseCompletions(profileId)
        deleteWorkoutSessions(profileId)
        deleteLandmarkSeries(profileId)
        deleteAssessmentSessions(profileId)
        deleteProfile(profileId)
    }
}
