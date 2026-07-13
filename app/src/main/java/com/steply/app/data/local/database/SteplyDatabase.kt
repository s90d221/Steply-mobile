package com.steply.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.steply.app.data.local.dao.AssessmentSessionDao
import com.steply.app.data.local.dao.CareAgentDao
import com.steply.app.data.local.dao.UserProfileDao
import com.steply.app.data.local.dao.AssessmentSummaryDao
import com.steply.app.data.local.dao.LandmarkSeriesDao
import com.steply.app.data.local.dao.ProfileDataDao
import com.steply.app.data.local.dao.WorkoutDao
import com.steply.app.data.local.entities.AssessmentSessionEntity
import com.steply.app.data.local.entities.AssessmentMessageReceiptEntity
import com.steply.app.data.local.entities.AssessmentResultReceiptEntity
import com.steply.app.data.local.entities.CareActionReceiptEntity
import com.steply.app.data.local.entities.CareAgentStateEntity
import com.steply.app.data.local.entities.CareDecisionLogEntity
import com.steply.app.data.local.entities.CareEventEntity
import com.steply.app.data.local.entities.UserProfileEntity
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.data.local.entities.ExerciseCompletionEntity
import com.steply.app.data.local.entities.LandmarkSeriesEntity
import com.steply.app.data.local.entities.WorkoutSessionEntity
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        AssessmentSessionEntity::class,
        AssessmentMessageReceiptEntity::class,
        AssessmentResultReceiptEntity::class,
        CareAgentStateEntity::class,
        CareEventEntity::class,
        CareDecisionLogEntity::class,
        CareActionReceiptEntity::class,
        AssessmentSummaryEntity::class,
        WorkoutSessionEntity::class,
        ExerciseCompletionEntity::class,
        LandmarkSeriesEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class SteplyDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun assessmentSessionDao(): AssessmentSessionDao
    abstract fun careAgentDao(): CareAgentDao
    abstract fun assessmentSummaryDao(): AssessmentSummaryDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun landmarkSeriesDao(): LandmarkSeriesDao
    abstract fun profileDataDao(): ProfileDataDao

    companion object {
        @Volatile
        private var instance: SteplyDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSchema(db)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSchema(db)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSchema(db)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_ASSESSMENT_SESSIONS_TABLE)
                db.execSQL(CREATE_ASSESSMENT_MESSAGE_RECEIPTS_TABLE)
                db.execSQL(CREATE_ASSESSMENT_RESULT_RECEIPTS_TABLE)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_CARE_AGENT_STATES_TABLE)
                db.execSQL(CREATE_CARE_EVENTS_TABLE)
                db.execSQL(CREATE_CARE_DECISION_LOGS_TABLE)
                db.execSQL(CREATE_CARE_ACTION_RECEIPTS_TABLE)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_events_profileId_occurredAt` ON `care_events` (`profileId`, `occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_events_payloadHash` ON `care_events` (`payloadHash`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_care_decision_logs_eventId` ON `care_decision_logs` (`eventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_decision_logs_profileId_createdAt` ON `care_decision_logs` (`profileId`, `createdAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_care_action_receipts_idempotencyKey` ON `care_action_receipts` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_action_receipts_decisionId` ON `care_action_receipts` (`decisionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_action_receipts_eventId` ON `care_action_receipts` (`eventId`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_ASSESSMENT_SUMMARIES_TABLE)
                db.execSQL(CREATE_WORKOUT_SESSIONS_TABLE)
                db.execSQL(CREATE_EXERCISE_COMPLETIONS_TABLE)
                db.execSQL(CREATE_LANDMARK_SERIES_TABLE)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_assessment_summaries_profileId_completedAt` ON `assessment_summaries` (`profileId`, `completedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_profileId_planId_startedAt` ON `workout_sessions` (`profileId`, `planId`, `startedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_completions_workoutSessionId_exerciseId` ON `exercise_completions` (`workoutSessionId`, `exerciseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_completions_profileId_completedAt` ON `exercise_completions` (`profileId`, `completedAt`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_landmark_series_messageId` ON `landmark_series` (`messageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_landmark_series_profileId_assessmentSessionId` ON `landmark_series` (`profileId`, `assessmentSessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_landmark_series_attemptId` ON `landmark_series` (`attemptId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_landmark_series_resultId` ON `landmark_series` (`resultId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `movement_history`")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )

        fun getInstance(context: Context): SteplyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SteplyDatabase::class.java,
                    "steply_mobile_remote.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }

        private fun normalizeSchema(db: SupportSQLiteDatabase) {
            normalizeUserProfiles(db)
            normalizeMovementHistory(db)
        }

        private fun normalizeUserProfiles(db: SupportSQLiteDatabase) {
            val columns = db.tableColumns("user_profiles")
            db.execSQL("DROP TABLE IF EXISTS `user_profiles_new`")
            db.execSQL(CREATE_USER_PROFILES_TABLE.replace("`user_profiles`", "`user_profiles_new`"))

            if (columns.isNotEmpty()) {
                db.execSQL(
                    """
                    INSERT INTO `user_profiles_new` (
                        `id`,
                        `displayName`,
                        `birthYear`,
                        `gender`,
                        `heightCm`,
                        `movementNotes`,
                        `safetyNote`,
                        `createdAt`,
                        `updatedAt`,
                        `archivedAt`
                    )
                    SELECT
                        ${textNotBlank(columns, fallback = "'legacy-profile-' || rowid", "id")},
                        ${textNotBlank(columns, fallback = "'Unnamed profile'", "displayName", "name")},
                        ${birthYearExpression(columns)},
                        ${nullableText(columns, "gender")},
                        ${nullableInt(columns, "heightCm", "height")},
                        ${nullableText(columns, "movementNotes", "mobilityNote")},
                        ${nullableText(columns, "safetyNote", "emergencyNote")},
                        ${intNotNull(columns, fallback = NOW_MS_SQL, "createdAt")},
                        ${intNotNull(columns, fallback = NOW_MS_SQL, "updatedAt")},
                        ${nullableInt(columns, "archivedAt")}
                    FROM `user_profiles`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `user_profiles`")
            }

            db.execSQL("ALTER TABLE `user_profiles_new` RENAME TO `user_profiles`")
        }

        private fun normalizeMovementHistory(db: SupportSQLiteDatabase) {
            val columns = db.tableColumns("movement_history")
            db.execSQL("DROP TABLE IF EXISTS `movement_history_new`")
            db.execSQL(CREATE_MOVEMENT_HISTORY_TABLE.replace("`movement_history`", "`movement_history_new`"))

            if (columns.isNotEmpty()) {
                db.execSQL(
                    """
                    INSERT INTO `movement_history_new` (
                        `id`,
                        `profileId`,
                        `profileName`,
                        `sessionId`,
                        `testType`,
                        `score`,
                        `repetitionCount`,
                        `durationSeconds`,
                        `recommendationLevel`,
                        `message`,
                        `flagsText`,
                        `rawJson`,
                        `createdAt`,
                        `receivedAt`
                    )
                    SELECT
                        ${textNotBlank(columns, fallback = "'legacy-history-' || rowid", "id")},
                        ${textNotBlank(columns, fallback = "'unknown-profile'", "profileId", "userId")},
                        ${nullableText(columns, "profileName", "displayName", "name")},
                        ${nullableText(columns, "sessionId")},
                        ${nullableText(columns, "testType", "selectedTest")},
                        ${nullableInt(columns, "score")},
                        ${nullableInt(columns, "repetitionCount", "count", "chairStandCount")},
                        ${nullableInt(columns, "durationSeconds", "elapsedSeconds")},
                        ${nullableText(columns, "recommendationLevel")},
                        ${nullableText(columns, "message")},
                        ${nullableText(columns, "flagsText")},
                        ${textNotBlank(columns, fallback = "'{}'", "rawJson", "resultJson")},
                        ${intNotNull(columns, fallback = NOW_MS_SQL, "createdAt", "endedAt")},
                        ${intNotNull(columns, fallback = NOW_MS_SQL, "receivedAt")}
                    FROM `movement_history`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `movement_history`")
            }

            db.execSQL("ALTER TABLE `movement_history_new` RENAME TO `movement_history`")
        }

        private fun SupportSQLiteDatabase.tableColumns(tableName: String): Set<String> {
            return query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    names += cursor.getString(nameIndex)
                }
                names
            }
        }

        private fun birthYearExpression(columns: Set<String>): String {
            val ageExpression = if ("age" in columns) {
                """
                CASE
                    WHEN CAST(`age` AS INTEGER) BETWEEN 0 AND 130
                    THEN CAST(strftime('%Y', 'now') AS INTEGER) - CAST(`age` AS INTEGER)
                    ELSE 1900
                END
                """.trimIndent()
            } else {
                "1900"
            }

            return if ("birthYear" in columns) {
                "COALESCE(CAST(`birthYear` AS INTEGER), $ageExpression)"
            } else {
                ageExpression
            }
        }

        private fun textNotBlank(
            columns: Set<String>,
            fallback: String,
            vararg candidates: String,
        ): String {
            val expressions = candidates
                .filter { it in columns }
                .map { "NULLIF(CAST(`$it` AS TEXT), '')" }
            return (expressions + fallback).joinToString(prefix = "COALESCE(", postfix = ")")
        }

        private fun nullableText(
            columns: Set<String>,
            vararg candidates: String,
        ): String {
            val expressions = candidates
                .filter { it in columns }
                .map { "NULLIF(CAST(`$it` AS TEXT), '')" }
            return when (expressions.size) {
                0 -> "NULL"
                1 -> expressions.single()
                else -> expressions.joinToString(prefix = "COALESCE(", postfix = ")")
            }
        }

        private fun intNotNull(
            columns: Set<String>,
            fallback: String,
            vararg candidates: String,
        ): String {
            val expressions = candidates
                .filter { it in columns }
                .map { "CAST(`$it` AS INTEGER)" }
            return (expressions + fallback).joinToString(prefix = "COALESCE(", postfix = ")")
        }

        private fun nullableInt(
            columns: Set<String>,
            vararg candidates: String,
        ): String {
            val expressions = candidates
                .filter { it in columns }
                .map { "CAST(`$it` AS INTEGER)" }
            return when (expressions.size) {
                0 -> "NULL"
                1 -> expressions.single()
                else -> expressions.joinToString(prefix = "COALESCE(", postfix = ")")
            }
        }

        private const val NOW_MS_SQL = "(CAST(strftime('%s', 'now') AS INTEGER) * 1000)"

        private const val CREATE_USER_PROFILES_TABLE = """
            CREATE TABLE IF NOT EXISTS `user_profiles` (
                `id` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `birthYear` INTEGER NOT NULL,
                `gender` TEXT,
                `heightCm` INTEGER,
                `movementNotes` TEXT,
                `safetyNote` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `archivedAt` INTEGER,
                PRIMARY KEY(`id`)
            )
        """

        private const val CREATE_MOVEMENT_HISTORY_TABLE = """
            CREATE TABLE IF NOT EXISTS `movement_history` (
                `id` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `profileName` TEXT,
                `sessionId` TEXT,
                `testType` TEXT,
                `score` INTEGER,
                `repetitionCount` INTEGER,
                `durationSeconds` INTEGER,
                `recommendationLevel` TEXT,
                `message` TEXT,
                `flagsText` TEXT,
                `rawJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `receivedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """

        private const val CREATE_ASSESSMENT_SESSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS `assessment_sessions` (
                `assessmentSessionId` TEXT NOT NULL,
                `connectionSessionId` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `serverUrl` TEXT NOT NULL,
                `candidateServerUrlsJson` TEXT NOT NULL,
                `expiresAtEpochMs` INTEGER NOT NULL,
                `pairingToken` TEXT NOT NULL,
                `tlsCertSha256` TEXT,
                `revision` INTEGER NOT NULL,
                `lastMessageId` TEXT NOT NULL,
                `envelopeJson` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`assessmentSessionId`)
            )
        """

        private const val CREATE_ASSESSMENT_MESSAGE_RECEIPTS_TABLE = """
            CREATE TABLE IF NOT EXISTS `assessment_message_receipts` (
                `messageId` TEXT NOT NULL,
                `assessmentSessionId` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `receivedAt` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`)
            )
        """

        private const val CREATE_ASSESSMENT_RESULT_RECEIPTS_TABLE = """
            CREATE TABLE IF NOT EXISTS `assessment_result_receipts` (
                `resultId` TEXT NOT NULL,
                `assessmentSessionId` TEXT NOT NULL,
                `assessmentType` TEXT NOT NULL,
                `attemptId` TEXT NOT NULL,
                `resultSignature` TEXT NOT NULL,
                PRIMARY KEY(`resultId`)
            )
        """

        private const val CREATE_CARE_AGENT_STATES_TABLE = """
            CREATE TABLE IF NOT EXISTS `care_agent_states` (
                `profileId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `stateVersion` INTEGER NOT NULL,
                `assessmentSessionId` TEXT NOT NULL,
                `assessmentRevision` INTEGER NOT NULL,
                `prescriptionPlanId` TEXT,
                `professionalApprovalId` TEXT,
                `latestDecisionId` TEXT,
                `inputStateJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`)
            )
        """

        private const val CREATE_CARE_EVENTS_TABLE = """
            CREATE TABLE IF NOT EXISTS `care_events` (
                `eventId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `eventType` TEXT NOT NULL,
                `sourceEventId` TEXT NOT NULL,
                `occurredAt` INTEGER NOT NULL,
                `payloadHash` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`eventId`)
            )
        """

        private const val CREATE_CARE_DECISION_LOGS_TABLE = """
            CREATE TABLE IF NOT EXISTS `care_decision_logs` (
                `decisionId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `selectedBranch` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `observedStateJson` TEXT NOT NULL,
                `candidateActionsJson` TEXT NOT NULL,
                `guardrailResultsJson` TEXT NOT NULL,
                `candidateDecisionsJson` TEXT NOT NULL,
                `executionResultsJson` TEXT NOT NULL,
                `completedStagesJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                PRIMARY KEY(`decisionId`)
            )
        """

        private const val CREATE_CARE_ACTION_RECEIPTS_TABLE = """
            CREATE TABLE IF NOT EXISTS `care_action_receipts` (
                `actionId` TEXT NOT NULL,
                `actionSchemaVersion` TEXT NOT NULL,
                `toolResultSchemaVersion` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `decisionId` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `actionType` TEXT NOT NULL,
                `toolId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `requestJson` TEXT NOT NULL,
                `attemptResultsJson` TEXT NOT NULL,
                `resultCode` TEXT,
                `resultReference` TEXT,
                `retryable` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`actionId`)
            )
        """

        private const val CREATE_ASSESSMENT_SUMMARIES_TABLE = """
            CREATE TABLE IF NOT EXISTS `assessment_summaries` (
                `assessmentSessionId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `completedAt` INTEGER NOT NULL,
                `risk` TEXT NOT NULL,
                `vulnerabilityIdsJson` TEXT NOT NULL,
                `chairStandRepetitions` INTEGER NOT NULL,
                `sideBySideSeconds` REAL NOT NULL,
                `semiTandemSeconds` REAL NOT NULL,
                `tandemSeconds` REAL NOT NULL,
                `oneLegSeconds` REAL NOT NULL,
                `valid` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`assessmentSessionId`)
            )
        """

        private const val CREATE_WORKOUT_SESSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                `workoutSessionId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `planId` TEXT NOT NULL,
                `prescribedExerciseIdsJson` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`workoutSessionId`)
            )
        """

        private const val CREATE_EXERCISE_COMPLETIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS `exercise_completions` (
                `completionId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `workoutSessionId` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `planId` TEXT NOT NULL,
                `exerciseId` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `completedAt` INTEGER NOT NULL,
                PRIMARY KEY(`completionId`)
            )
        """

        private const val CREATE_LANDMARK_SERIES_TABLE = """
            CREATE TABLE IF NOT EXISTS `landmark_series` (
                `seriesId` TEXT NOT NULL,
                `schemaVersion` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `profileId` TEXT NOT NULL,
                `assessmentSessionId` TEXT NOT NULL,
                `attemptId` TEXT NOT NULL,
                `analysisSessionId` TEXT NOT NULL,
                `resultId` TEXT NOT NULL,
                `assessmentType` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `targetFps` INTEGER NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER NOT NULL,
                `sampleCount` INTEGER NOT NULL,
                `samplesJson` TEXT NOT NULL,
                `storedAt` INTEGER NOT NULL,
                PRIMARY KEY(`seriesId`)
            )
        """
    }
}
