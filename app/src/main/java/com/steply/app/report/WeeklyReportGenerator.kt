package com.steply.app.report

import com.steply.app.domain.model.MovementHistory
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class WeeklyReport(
    val profileName: String,
    val periodStartMs: Long,
    val periodEndMs: Long,
    val generatedAtMs: Long,
    val sourceCount: Int,
    val latestRiskLevel: String?,
    val weakAreas: List<String>,
    val trends: List<WeeklyTrend>,
    val exerciseCompletion: WeeklyExerciseCompletion?,
)

data class WeeklyTrend(
    val label: String,
    val values: List<Int>,
    val delta: Int,
    val directionText: String,
)

data class WeeklyExerciseCompletion(
    val percent: Int,
    val completedCount: Int?,
    val totalCount: Int?,
)

object WeeklyReportGenerator {
    private const val WeekMs = 7L * 24L * 60L * 60L * 1000L

    fun generate(
        items: List<MovementHistory>,
        selectedProfileId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): WeeklyReport? {
        val scopedItems = selectedProfileId?.let { id ->
            items.filter { it.profileId == id }
        } ?: return null
        if (scopedItems.isEmpty()) return null

        val sortedItems = scopedItems.sortedBy { it.receivedAt }
        val recentItems = sortedItems.filter { it.receivedAt >= nowMs - WeekMs }
        val latestItem = sortedItems.last()
        val trendItems = recentItems.ifEmpty { sortedItems }

        return WeeklyReport(
            profileName = latestItem.profileName?.takeIf { it.isNotBlank() } ?: "Selected profile",
            periodStartMs = nowMs - WeekMs,
            periodEndMs = nowMs,
            generatedAtMs = nowMs,
            sourceCount = recentItems.size,
            latestRiskLevel = latestItem.pcStoredRiskLevel(),
            weakAreas = latestItem.pcStoredWeakAreas(),
            trends = trendItems.trends(),
            exerciseCompletion = recentItems.weeklyExerciseCompletion(),
        )
    }

    private fun List<MovementHistory>.trends(): List<WeeklyTrend> {
        return WeeklyReportCategory.entries.mapNotNull { category ->
            val values = filter { it.category() == category }
                .mapNotNull { it.displayMetricValue(category) }
                .takeLast(5)
            if (values.size < 2) return@mapNotNull null

            val delta = values.last() - values.first()
            WeeklyTrend(
                label = category.displayName,
                values = values,
                delta = delta,
                directionText = when {
                    delta > 0 -> "up ${delta.toSignedText()}"
                    delta < 0 -> "down ${delta.toSignedText()}"
                    else -> "no change"
                },
            )
        }
    }

    private fun List<MovementHistory>.weeklyExerciseCompletion(): WeeklyExerciseCompletion? {
        val completions = mapNotNull { it.pcStoredExerciseCompletion() }
        if (completions.isEmpty()) return null

        val totalCompleted = completions.mapNotNull { it.completedCount }.sum()
        val totalCount = completions.mapNotNull { it.totalCount }.sum()
        if (totalCount > 0) {
            return WeeklyExerciseCompletion(
                percent = ((totalCompleted.toFloat() / totalCount.toFloat()) * 100f).roundToInt().coerceIn(0, 100),
                completedCount = totalCompleted,
                totalCount = totalCount,
            )
        }

        return WeeklyExerciseCompletion(
            percent = completions.map { it.percent }.average().roundToInt().coerceIn(0, 100),
            completedCount = null,
            totalCount = null,
        )
    }
}

object WeeklyReportFormatter {
    fun format(report: WeeklyReport): String {
        val lines = mutableListOf<String>()
        lines += "Steply weekly movement report"
        lines += "Shared by the phone user. This report contains saved PC final results only."
        lines += ""
        lines += "Profile: ${report.profileName}"
        lines += "Period: ${report.periodStartMs.shortDate()} - ${report.periodEndMs.shortDate()}"
        lines += "Generated: ${report.generatedAtMs.dateTime()}"
        lines += ""
        lines += "Latest stored risk level: ${report.latestRiskLevel ?: "No stored risk level"}"
        lines += "Stored weak areas: ${report.weakAreas.takeIf { it.isNotEmpty() }?.joinToString() ?: "No stored weak areas"}"
        lines += "This week's exercise completion: ${report.exerciseCompletion?.toDisplayText() ?: "No stored completion data"}"
        lines += ""
        lines += "Recent score direction"
        if (report.trends.isEmpty()) {
            lines += "- Not enough saved results to compare direction."
        } else {
            report.trends.forEach { trend ->
                lines += "- ${trend.label}: ${trend.values.joinToString(" -> ")} (${trend.directionText})"
            }
        }
        lines += ""
        lines += "Privacy note: no camera frames, pose landmarks, raw JSON, profile id, or session id are included."
        lines += "Clinical note: score direction is an arithmetic display trend, not a diagnosis."
        return lines.joinToString("\n")
    }
}

private enum class WeeklyReportCategory(val displayName: String) {
    STANDING("Standing"),
    CHAIR_STAND("Chair Stand"),
    TUG("TUG"),
    OTHER("Other"),
}

private fun MovementHistory.category(): WeeklyReportCategory {
    val normalized = testType
        ?.lowercase()
        ?.replace("_", " ")
        ?.trim()
        .orEmpty()

    return when {
        normalized.contains("standing") -> WeeklyReportCategory.STANDING
        normalized.contains("posture") -> WeeklyReportCategory.STANDING
        normalized.contains("chair") -> WeeklyReportCategory.CHAIR_STAND
        normalized.contains("stand up") -> WeeklyReportCategory.CHAIR_STAND
        normalized.contains("tug") -> WeeklyReportCategory.TUG
        normalized.contains("timed up and go") -> WeeklyReportCategory.TUG
        else -> WeeklyReportCategory.OTHER
    }
}

private fun MovementHistory.displayMetricValue(category: WeeklyReportCategory): Int? {
    return when (category) {
        WeeklyReportCategory.CHAIR_STAND -> repetitionCount ?: score
        WeeklyReportCategory.TUG -> score ?: durationSeconds
        WeeklyReportCategory.STANDING -> score ?: durationSeconds
        WeeklyReportCategory.OTHER -> score ?: repetitionCount ?: durationSeconds
    }
}

private fun MovementHistory.pcStoredRiskLevel(): String? {
    val json = finalJsonOrNull() ?: return null
    return json.firstNonBlank(
        "riskLevel",
        "fallRiskLevel",
        "steadiRiskLevel",
        "riskCategory",
    ) ?: json.optJSONObject("risk")?.firstNonBlank("level", "category")
}

private fun MovementHistory.pcStoredWeakAreas(): List<String> {
    val json = finalJsonOrNull() ?: return emptyList()
    return json.firstStringList(
        "weakAreas",
        "weaknesses",
        "vulnerableAreas",
        "weakBodyAreas",
        "weakFunctions",
    ).ifEmpty {
        json.firstNonBlank(
            "weakAreasText",
            "weaknessSummary",
            "vulnerableArea",
            "weakArea",
        )?.splitList().orEmpty()
    }
}

private fun MovementHistory.pcStoredExerciseCompletion(): WeeklyExerciseCompletion? {
    val json = finalJsonOrNull() ?: return null
    val completed = json.firstInt(
        "exerciseCompletedCount",
        "completedExercises",
        "weeklyCompletedExercises",
    )
    val total = json.firstInt(
        "exerciseTotalCount",
        "totalExercises",
        "weeklyTotalExercises",
    )
    if (completed != null && total != null && total > 0) {
        return WeeklyExerciseCompletion(
            percent = ((completed.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100),
            completedCount = completed,
            totalCount = total,
        )
    }

    val percent = json.firstPercent(
        "exerciseCompletionRate",
        "weeklyExerciseCompletionRate",
        "exerciseAdherenceRate",
        "adherenceRate",
        "completionRate",
    ) ?: return null

    return WeeklyExerciseCompletion(
        percent = percent,
        completedCount = completed,
        totalCount = total,
    )
}

private fun MovementHistory.finalJsonOrNull(): JSONObject? {
    return runCatching { JSONObject(rawJson) }.getOrNull()
}

private fun JSONObject.firstNonBlank(vararg names: String): String? {
    for (name in names) {
        val value = optString(name).trim()
        if (value.isNotBlank()) return value
    }
    return null
}

private fun JSONObject.firstStringList(vararg names: String): List<String> {
    for (name in names) {
        val values = optJSONArray(name)?.toStringList().orEmpty()
        if (values.isNotEmpty()) return values
    }
    return emptyList()
}

private fun JSONObject.firstInt(vararg names: String): Int? {
    for (name in names) {
        if (!has(name) || isNull(name)) continue
        val value = runCatching { getDouble(name).roundToInt() }.getOrNull()
        if (value != null) return value
    }
    return null
}

private fun JSONObject.firstPercent(vararg names: String): Int? {
    for (name in names) {
        if (!has(name) || isNull(name)) continue
        val value = runCatching { getDouble(name) }.getOrNull() ?: continue
        return if (value <= 1.0) {
            (value * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            value.roundToInt().coerceIn(0, 100)
        }
    }
    return null
}

private fun JSONArray.toStringList(): List<String> {
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = opt(index)
        when (value) {
            is JSONObject -> values += value.firstNonBlank("label", "name", "title", "area").orEmpty()
            else -> values += optString(index)
        }
    }
    return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

private fun String.splitList(): List<String> {
    return split(",", "\n", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun Int.toSignedText(): String {
    return if (this > 0) "+$this" else toString()
}

private fun WeeklyExerciseCompletion.toDisplayText(): String {
    return if (completedCount != null && totalCount != null) {
        "$percent% ($completedCount/$totalCount)"
    } else {
        "$percent%"
    }
}

private fun Long.shortDate(): String {
    return SimpleDateFormat("MMM d", Locale.US).format(Date(this))
}

private fun Long.dateTime(): String {
    return SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(this))
}
