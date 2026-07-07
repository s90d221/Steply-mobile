package com.steply.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.steply.app.data.local.dao.MovementHistoryDao
import com.steply.app.data.local.dao.UserProfileDao
import com.steply.app.data.local.entities.MovementHistoryEntity
import com.steply.app.data.local.entities.UserProfileEntity
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserProfileEntity::class, MovementHistoryEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class SteplyDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun movementHistoryDao(): MovementHistoryDao

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

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
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
            return if (expressions.isEmpty()) "NULL" else expressions.joinToString(prefix = "COALESCE(", postfix = ")")
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
            return if (expressions.isEmpty()) "NULL" else expressions.joinToString(prefix = "COALESCE(", postfix = ")")
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
    }
}
