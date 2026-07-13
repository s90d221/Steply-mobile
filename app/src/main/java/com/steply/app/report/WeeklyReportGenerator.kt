package com.steply.app.report

import com.steply.app.domain.model.BalanceStage
import com.steply.app.sync.SteplyLocalReportData
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
    val previousRiskLevel: String?,
    val riskChanged: Boolean,
    val weakAreas: List<String>,
    val recentAssessments: List<WeeklyRecentAssessment>,
    val trends: List<WeeklyTrend>,
    val exerciseCompletion: WeeklyExerciseCompletion?,
    val adherence: WeeklyAdherence,
    val safetyEvents: List<String>,
    val fallHistory: WeeklyFallHistory,
    val invalidAssessmentRatio: Double,
    val recommendations: List<String>,
    val agentActions: List<WeeklyAgentAction>,
)

data class WeeklyRecentAssessment(
    val completedAtMs: Long,
    val riskLevel: String,
    val chairStandRepetitions: Int,
    val balanceSecondsByStage: Map<BalanceStage, Double>,
)

data class WeeklyTrend(
    val label: String,
    val values: List<Double>,
    val delta: Double,
    val directionText: String,
)

data class WeeklyExerciseCompletion(val percent: Int, val completedCount: Int, val totalCount: Int)

data class WeeklyAdherence(val completedSessions: Int, val targetSessions: Int) {
    val percent: Int
        get() = if (targetSessions <= 0) 0 else
            ((completedSessions.toDouble() / targetSessions) * 100.0).roundToInt().coerceIn(0, 100)
}

data class WeeklyFallHistory(val reportedFallCount: Int, val injuriousFall: Boolean)

data class WeeklyAgentAction(
    val actionType: String,
    val reasonCode: String,
    val executionStatus: String,
    val occurredAtMs: Long,
)

/** Maps the strict shared snapshot to display text without re-deriving clinical state. */
object WeeklyReportGenerator {
    fun generate(contract: SteplyLocalReportData): WeeklyReport {
        val snapshot = contract.weeklyReport
        val adherence = WeeklyAdherence(
            completedSessions = snapshot.adherence.completedSessions,
            targetSessions = snapshot.adherence.targetSessions,
        )
        return WeeklyReport(
            profileName = contract.profile.displayName,
            periodStartMs = snapshot.periodStart,
            periodEndMs = snapshot.periodEnd,
            generatedAtMs = snapshot.generatedAt,
            sourceCount = snapshot.recentAssessments.size,
            latestRiskLevel = snapshot.latestRiskLevel?.name,
            previousRiskLevel = snapshot.previousRiskLevel?.name,
            riskChanged = snapshot.riskChanged,
            weakAreas = snapshot.vulnerabilityIds.map { it.name },
            recentAssessments = snapshot.recentAssessments.map {
                WeeklyRecentAssessment(
                    completedAtMs = it.completedAt,
                    riskLevel = it.risk.name,
                    chairStandRepetitions = it.chairStandRepetitions,
                    balanceSecondsByStage = it.balanceSecondsByStage,
                )
            },
            trends = buildTrends(snapshot.recentAssessments.map { assessment ->
                linkedMapOf(
                    "30 sec Chair Stand" to assessment.chairStandRepetitions.toDouble(),
                    *BalanceStage.entries.map { stage ->
                        "4-Stage Balance · ${stage.name.replace('_', ' ')}" to
                            assessment.balanceSecondsByStage.getValue(stage)
                    }.toTypedArray(),
                )
            }),
            exerciseCompletion = WeeklyExerciseCompletion(
                percent = adherence.percent,
                completedCount = adherence.completedSessions,
                totalCount = adherence.targetSessions,
            ),
            adherence = adherence,
            safetyEvents = snapshot.safetyEvents.map { it.type },
            fallHistory = WeeklyFallHistory(
                reportedFallCount = snapshot.fallReports.size,
                injuriousFall = snapshot.fallReports.any { it.injurious },
            ),
            invalidAssessmentRatio = snapshot.invalidAttempts.ratio,
            recommendations = listOf(snapshot.recommendation.status.name),
            agentActions = snapshot.agentRationale.map {
                WeeklyAgentAction(
                    actionType = it.actionType,
                    reasonCode = it.reasonCodes.joinToString(","),
                    executionStatus = it.executionStatus,
                    occurredAtMs = it.occurredAt,
                )
            },
        )
    }

    private fun buildTrends(points: List<Map<String, Double>>): List<WeeklyTrend> {
        val labels = points.flatMap { it.keys }.distinct()
        return labels.mapNotNull { label ->
            val values = points.mapNotNull { it[label] }
            if (values.size < 2) return@mapNotNull null
            val delta = values.last() - values.first()
            WeeklyTrend(
                label = label,
                values = values,
                delta = delta,
                directionText = when {
                    delta > 0.0 -> "up ${delta.signedText()}"
                    delta < 0.0 -> "down ${delta.signedText()}"
                    else -> "no change"
                },
            )
        }
    }
}

object WeeklyReportFormatter {
    fun format(report: WeeklyReport): String = buildList {
        add("Steply weekly movement report")
        add("Shared by the phone user. This report contains deterministic saved summaries only.")
        add("")
        add("Profile: ${report.profileName}")
        add("Period: ${report.periodStartMs.shortDate()} - ${report.periodEndMs.shortDate()}")
        add("Generated: ${report.generatedAtMs.dateTime()}")
        add("")
        add("Risk level: ${report.latestRiskLevel ?: "No scored result"}")
        add("Risk change: ${report.previousRiskLevel ?: "No previous-week baseline"} -> ${report.latestRiskLevel ?: "No result"}")
        add("Active vulnerabilities: ${report.weakAreas.ifEmpty { listOf("None") }.joinToString()}")
        add("Weekly adherence: ${report.adherence.completedSessions}/${report.adherence.targetSessions} sessions (${report.adherence.percent}%)")
        add("Invalid assessment ratio: ${(report.invalidAssessmentRatio * 100).roundToInt()}%")
        add("")
        add("Recent valid assessments")
        report.recentAssessments.forEach { assessment ->
            add(
                "- ${assessment.completedAtMs.shortDate()}: ${assessment.riskLevel}; " +
                    "chair ${assessment.chairStandRepetitions}; " +
                    BalanceStage.entries.joinToString { stage ->
                        "${stage.name} ${assessment.balanceSecondsByStage.getValue(stage).metricText()}s"
                    },
            )
        }
        if (report.recentAssessments.isEmpty()) add("- No valid assessment summary in the current snapshot.")
        add("")
        add("Recent canonical direction")
        if (report.trends.isEmpty()) add("- Not enough valid summaries to compare direction.")
        report.trends.forEach { trend ->
            add("- ${trend.label}: ${trend.values.joinToString(" -> ") { it.metricText() }} (${trend.directionText})")
        }
        add("")
        add("Safety events: ${report.safetyEvents.ifEmpty { listOf("None") }.joinToString()}")
        add("Falls: ${report.fallHistory.reportedFallCount}; injurious fall: ${report.fallHistory.injuriousFall}")
        add("Recommendation: ${report.recommendations.joinToString()}")
        add("")
        add("Care-agent actions")
        if (report.agentActions.isEmpty()) add("- No care-agent action recorded this week.")
        report.agentActions.forEach { action ->
            add("- ${action.actionType}: ${action.reasonCode} (${action.executionStatus})")
        }
        add("")
        add("Privacy note: no camera frames, pose landmarks, raw JSON, profile id, or session id are included.")
        add("Clinical note: displayed direction is arithmetic only and does not change the deterministic risk or prescription.")
    }.joinToString("\n")
}

private fun Double.signedText(): String = String.format(Locale.US, "%+.1f", this)
private fun Double.metricText(): String = String.format(Locale.US, "%.1f", this)
private fun Long.shortDate(): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(this))
private fun Long.dateTime(): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(this))
