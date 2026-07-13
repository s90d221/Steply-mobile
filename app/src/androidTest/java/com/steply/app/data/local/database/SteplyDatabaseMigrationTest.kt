package com.steply.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class SteplyDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SteplyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun deleteDatabase() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .deleteDatabase(TEST_DB)
    }

    @Test
    fun migrateVersion3To4_preservesMovementHistoryAndConvertsAgeToBirthYear() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_profiles` (
                    `id` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `age` INTEGER NOT NULL,
                    `gender` TEXT,
                    `heightCm` INTEGER,
                    `movementNotes` TEXT,
                    `safetyNote` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `archivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            execSQL(
                """
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
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `user_profiles` (
                    `id`,
                    `displayName`,
                    `age`,
                    `gender`,
                    `heightCm`,
                    `movementNotes`,
                    `safetyNote`,
                    `createdAt`,
                    `updatedAt`,
                    `archivedAt`
                ) VALUES (
                    'profile-1',
                    'Hong',
                    72,
                    'female',
                    160,
                    'uses cane',
                    'stop on dizziness',
                    1000,
                    2000,
                    NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `movement_history` (
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
                ) VALUES (
                    'history-1',
                    'profile-1',
                    'Hong',
                    'session-1',
                    'chair-stand',
                    88,
                    12,
                    30,
                    'medium',
                    'keep training',
                    'flag-a',
                    '{"id":"history-1"}',
                    3000,
                    4000
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT COUNT(*) FROM `movement_history`").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT `id`, `profileId`, `rawJson`, `receivedAt` FROM `movement_history`").use { cursor ->
            cursor.moveToFirst()
            assertEquals("history-1", cursor.getString(0))
            assertEquals("profile-1", cursor.getString(1))
            assertEquals("""{"id":"history-1"}""", cursor.getString(2))
            assertEquals(4000L, cursor.getLong(3))
        }
        db.query("SELECT `birthYear` FROM `user_profiles` WHERE `id` = 'profile-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(Calendar.getInstance().get(Calendar.YEAR) - 72, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrateVersion1To4_createsHistoryTableWithoutDroppingProfiles() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_profiles` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `age` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `user_profiles` (
                    `id`,
                    `name`,
                    `age`,
                    `createdAt`,
                    `updatedAt`
                ) VALUES (
                    'legacy-profile',
                    'Legacy User',
                    65,
                    1000,
                    2000
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT `id`, `displayName` FROM `user_profiles`").use { cursor ->
            cursor.moveToFirst()
            assertEquals("legacy-profile", cursor.getString(0))
            assertEquals("Legacy User", cursor.getString(1))
        }
        db.query("SELECT COUNT(*) FROM `movement_history`").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrateVersion4To5_addsPersistentAssessmentSessionsWithoutDroppingExistingData() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_profiles` (
                    `id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `birthYear` INTEGER NOT NULL,
                    `gender` TEXT, `heightCm` INTEGER, `movementNotes` TEXT, `safetyNote` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `archivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `movement_history` (
                    `id` TEXT NOT NULL, `profileId` TEXT NOT NULL, `profileName` TEXT,
                    `sessionId` TEXT, `testType` TEXT, `score` INTEGER, `repetitionCount` INTEGER,
                    `durationSeconds` INTEGER, `recommendationLevel` TEXT, `message` TEXT,
                    `flagsText` TEXT, `rawJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                    `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO `user_profiles` (`id`,`displayName`,`birthYear`,`createdAt`,`updatedAt`) " +
                    "VALUES ('profile-v4','Existing',1950,1,1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT COUNT(*) FROM `user_profiles` WHERE `id` = 'profile-v4'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `assessment_sessions`").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun S4_MIGRATION_01_migrateVersion5To6AddsDurableCareTables() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        listOf(
            "care_agent_states",
            "care_events",
            "care_decision_logs",
            "care_action_receipts",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
        db.query("PRAGMA table_info(`care_action_receipts`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(true, "attemptResultsJson" in columns)
            assertEquals(true, "actionSchemaVersion" in columns)
            assertEquals(true, "toolResultSchemaVersion" in columns)
        }
        db.close()
    }

    @Test
    fun REQ_S5_DB_01_migrateVersion6To7AddsTypedStage5TablesAndPreservesProfiles() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO `user_profiles` (`id`,`displayName`,`birthYear`,`createdAt`,`updatedAt`) " +
                    "VALUES ('profile-v6','Existing',1950,1,1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT COUNT(*) FROM `user_profiles` WHERE `id` = 'profile-v6'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        listOf(
            "assessment_summaries",
            "workout_sessions",
            "exercise_completions",
            "landmark_series",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
        db.query("PRAGMA table_info(`landmark_series`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(true, "samplesJson" in columns)
            assertEquals(false, "rawFrames" in columns)
        }
        db.close()
    }

    @Test
    fun REQ_S6_M01_migrateVersion7To8DropsLegacyHistoryAndPreservesCanonicalData() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO `user_profiles` (`id`,`displayName`,`birthYear`,`createdAt`,`updatedAt`) " +
                    "VALUES ('profile-v7','Existing',1950,1,1)",
            )
            execSQL(
                "INSERT INTO `movement_history` (`id`,`profileId`,`rawJson`,`createdAt`,`receivedAt`) " +
                    "VALUES ('legacy-history','profile-v7','{}',1,1)",
            )
            execSQL(
                "INSERT INTO `assessment_summaries` " +
                    "(`assessmentSessionId`,`schemaVersion`,`profileId`,`completedAt`,`risk`,`vulnerabilityIdsJson`," +
                    "`chairStandRepetitions`,`sideBySideSeconds`,`semiTandemSeconds`,`tandemSeconds`,`oneLegSeconds`,`valid`,`updatedAt`) " +
                    "VALUES ('assessment-v7','assessment_summary.v1','profile-v7',1,'LOW','[]',10,10,10,10,1,1,1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            *SteplyDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT COUNT(*) FROM `user_profiles` WHERE `id` = 'profile-v7'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `assessment_summaries` WHERE `assessmentSessionId` = 'assessment-v7'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'movement_history'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "steply-migration-test.db"
    }
}
