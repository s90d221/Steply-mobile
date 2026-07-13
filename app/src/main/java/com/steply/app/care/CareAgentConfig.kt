package com.steply.app.care

data class CareAgentConfig(
    val configVersion: String,
    val recentAssessmentLimit: Int,
    val decliningConsecutiveAssessments: Int,
    val reassessmentIntervalMs: Long,
    val adherenceTargetSessionsPerWeek: Int,
    val lowAdherenceConsecutiveWeeks: Int,
    val lowAdherenceMaximumCompletedSessions: Int,
    val weeklyReportIntervalMs: Long,
    val agentCycleIntervalHours: Long,
)

object CareAgentConfigV1 {
    val value = CareAgentConfig(
        configVersion = "care_agent_operational.v1",
        recentAssessmentLimit = 5,
        decliningConsecutiveAssessments = 3,
        reassessmentIntervalMs = 28L * 24L * 60L * 60L * 1000L,
        adherenceTargetSessionsPerWeek = 3,
        lowAdherenceConsecutiveWeeks = 2,
        lowAdherenceMaximumCompletedSessions = 1,
        weeklyReportIntervalMs = 7L * 24L * 60L * 60L * 1000L,
        agentCycleIntervalHours = 24L,
    )
}
