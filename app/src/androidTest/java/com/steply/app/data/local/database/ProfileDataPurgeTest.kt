package com.steply.app.data.local.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDataPurgeTest {
    private lateinit var database: SteplyDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SteplyDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun REQ_S5_PROFILE_DELETE_transactionRemovesOnlySelectedProfileGraph() = runBlocking {
        val db = database.openHelper.writableDatabase
        listOf("delete-me", "keep-me").forEachIndexed { index, profileId ->
            val suffix = index + 1
            db.execSQL("INSERT INTO user_profiles(id,displayName,birthYear,createdAt,updatedAt) VALUES(?,?,1950,1,1)", arrayOf(profileId, profileId))
            db.execSQL(
                "INSERT INTO assessment_sessions(assessmentSessionId,connectionSessionId,profileId,serverUrl,candidateServerUrlsJson,expiresAtEpochMs,pairingToken,revision,lastMessageId,envelopeJson,isActive,createdAt,updatedAt) VALUES(?,?,?,'http://local','[]',1,'token',1,?,'{}',0,1,1)",
                arrayOf("assessment-$suffix", "connection-$suffix", profileId, "message-$suffix"),
            )
            db.execSQL("INSERT INTO assessment_message_receipts(messageId,assessmentSessionId,revision,receivedAt) VALUES(?,?,1,1)", arrayOf("message-$suffix", "assessment-$suffix"))
            db.execSQL("INSERT INTO assessment_result_receipts(resultId,assessmentSessionId,assessmentType,attemptId,resultSignature) VALUES(?,?,'CHAIR_STAND_30S',?,'signature')", arrayOf("result-$suffix", "assessment-$suffix", "attempt-$suffix"))
            db.execSQL("INSERT INTO assessment_summaries(assessmentSessionId,schemaVersion,profileId,completedAt,risk,vulnerabilityIdsJson,chairStandRepetitions,sideBySideSeconds,semiTandemSeconds,tandemSeconds,oneLegSeconds,valid,updatedAt) VALUES(?,'assessment_summary.v1',?,1,'LOW','[]',10,10,10,10,1,1,1)", arrayOf("assessment-$suffix", profileId))
            db.execSQL("INSERT INTO workout_sessions(workoutSessionId,schemaVersion,profileId,planId,prescribedExerciseIdsJson,status,startedAt,updatedAt) VALUES(?,'workout_session.v1',?,?,'[\"W1\"]','IN_PROGRESS',1,1)", arrayOf("workout-$suffix", profileId, "plan-$suffix"))
            db.execSQL("INSERT INTO exercise_completions(completionId,schemaVersion,workoutSessionId,profileId,planId,exerciseId,source,completedAt) VALUES(?,'exercise_completion.v1',?,?,?,'W1','USER_CONFIRMED',1)", arrayOf("completion-$suffix", "workout-$suffix", profileId, "plan-$suffix"))
            db.execSQL("INSERT INTO landmark_series(seriesId,schemaVersion,messageId,profileId,assessmentSessionId,attemptId,analysisSessionId,resultId,assessmentType,status,targetFps,startedAt,completedAt,sampleCount,samplesJson,storedAt) VALUES(?,'landmark_series.v1',?,?,?,?,?,?,'CHAIR_STAND_30S','VALID',30,1,2,0,'[]',3)", arrayOf("series-$suffix", "landmark-message-$suffix", profileId, "assessment-$suffix", "attempt-$suffix", "analysis-$suffix", "result-$suffix"))
            db.execSQL("INSERT INTO care_agent_states(profileId,schemaVersion,stateVersion,assessmentSessionId,assessmentRevision,inputStateJson,updatedAt) VALUES(?,'care_agent_state.v1',1,?,1,'{}',1)", arrayOf(profileId, "assessment-$suffix"))
            db.execSQL("INSERT INTO care_events(eventId,schemaVersion,profileId,eventType,sourceEventId,occurredAt,payloadHash,payloadJson,status,createdAt,updatedAt) VALUES(?,'care_event.v1',?,'SESSION_START',?,1,?,'{}','PENDING',1,1)", arrayOf("event-$suffix", profileId, "source-$suffix", "hash-$suffix"))
            db.execSQL("INSERT INTO care_decision_logs(decisionId,schemaVersion,eventId,profileId,selectedBranch,status,observedStateJson,candidateActionsJson,guardrailResultsJson,candidateDecisionsJson,executionResultsJson,completedStagesJson,createdAt) VALUES(?,'care_decision_log.v1',?,?,'MAINTAIN','COMPLETED','{}','[]','[]','[]','[]','[]',1)", arrayOf("decision-$suffix", "event-$suffix", profileId))
            db.execSQL("INSERT INTO care_action_receipts(actionId,actionSchemaVersion,toolResultSchemaVersion,idempotencyKey,decisionId,eventId,profileId,actionType,toolId,status,requestJson,attemptResultsJson,retryable,createdAt,updatedAt) VALUES(?,'care_action.v1','care_tool_result.v1',?,?,?,?, 'MAINTAIN_PLAN','SCHEDULER','SUCCEEDED','{}','[]',0,1,1)", arrayOf("action-$suffix", "key-$suffix", "decision-$suffix", "event-$suffix", profileId))
        }

        database.profileDataDao().purgeProfile("delete-me")

        listOf(
            "user_profiles",
            "assessment_sessions",
            "assessment_summaries",
            "workout_sessions",
            "exercise_completions",
            "landmark_series",
            "care_agent_states",
            "care_events",
            "care_decision_logs",
            "care_action_receipts",
        ).forEach { table ->
            val profileColumn = if (table == "user_profiles") "id" else "profileId"
            db.query("SELECT COUNT(*) FROM $table WHERE $profileColumn = 'delete-me'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("$table delete scope", 0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM $table WHERE $profileColumn = 'keep-me'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("$table preserve scope", 1, cursor.getInt(0))
            }
        }
        db.query("SELECT COUNT(*) FROM assessment_message_receipts WHERE messageId = 'message-1'").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM assessment_result_receipts WHERE resultId = 'result-1'").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM assessment_message_receipts WHERE messageId = 'message-2'").use { cursor ->
            cursor.moveToFirst(); assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM assessment_result_receipts WHERE resultId = 'result-2'").use { cursor ->
            cursor.moveToFirst(); assertEquals(1, cursor.getInt(0))
        }
    }
}
