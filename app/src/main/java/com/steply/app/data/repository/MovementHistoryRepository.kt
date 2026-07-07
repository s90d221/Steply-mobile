package com.steply.app.data.repository

import com.steply.app.data.local.dao.MovementHistoryDao
import com.steply.app.data.local.entities.MovementHistoryEntity
import com.steply.app.domain.model.MovementHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class MovementHistoryRepository(
    private val movementHistoryDao: MovementHistoryDao,
) {
    fun observeAll(): Flow<List<MovementHistory>> {
        return movementHistoryDao.observeAll().map { list -> list.map { it.toDomain() } }
    }

    fun observeByProfileId(profileId: String): Flow<List<MovementHistory>> {
        return movementHistoryDao.observeByProfileId(profileId).map { list -> list.map { it.toDomain() } }
    }

    suspend fun getAll(): List<MovementHistory> {
        return movementHistoryDao.getAll().map { it.toDomain() }
    }

    suspend fun saveFromPcResult(resultJson: String) {
        val now = System.currentTimeMillis()
        val json = JSONObject(resultJson)
        val profile = json.optJSONObject("profile")
        val profileId = json.optString("userId").takeIf { it.isNotBlank() }
            ?: profile?.optString("id")?.takeIf { it.isNotBlank() }
            ?: "unknown-profile"
        val profileName = profile?.optString("displayName")?.takeIf { it.isNotBlank() }
            ?: profile?.optString("name")?.takeIf { it.isNotBlank() }
        // Persist scalar fields from the PC final result only. Mobile must not derive
        // fall-risk levels, weak body areas, or exercise recommendations from raw pose data.
        val features = json.optJSONObject("features")
        val flagsText = json.optJSONArray("flags")?.joinText()

        movementHistoryDao.upsert(
            MovementHistoryEntity(
                id = json.optString("id").takeIf { it.isNotBlank() }
                    ?: "${json.optString("sessionId", "session")}-${json.optLong("receivedAt", now)}",
                profileId = profileId,
                profileName = profileName,
                sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                testType = json.optString("selectedTest").takeIf { it.isNotBlank() }
                    ?: json.optString("testType").takeIf { it.isNotBlank() },
                score = json.optNullableInt("score"),
                repetitionCount = json.optNullableInt("repetitionCount")
                    ?: json.optNullableInt("count")
                    ?: features?.optNullableInt("chairStandCount"),
                durationSeconds = json.optNullableInt("durationSeconds")
                    ?: json.optNullableInt("elapsedSeconds"),
                recommendationLevel = json.optString("recommendationLevel").takeIf { it.isNotBlank() },
                message = json.optString("message").takeIf { it.isNotBlank() },
                flagsText = flagsText,
                rawJson = json.toString(),
                createdAt = json.optLong("createdAt", json.optLong("endedAt", now)),
                receivedAt = json.optLong("receivedAt", now),
            ),
        )
    }
}

private fun MovementHistoryEntity.toDomain(): MovementHistory {
    return MovementHistory(
        id = id,
        profileId = profileId,
        profileName = profileName,
        sessionId = sessionId,
        testType = testType,
        score = score,
        repetitionCount = repetitionCount,
        durationSeconds = durationSeconds,
        recommendationLevel = recommendationLevel,
        message = message,
        flagsText = flagsText,
        rawJson = rawJson,
        createdAt = createdAt,
        receivedAt = receivedAt,
    )
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getDouble(name).toInt() }.getOrNull()
}

private fun JSONArray.joinText(): String {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        values += optString(index)
    }
    return values.filter { it.isNotBlank() }.joinToString("\n")
}
