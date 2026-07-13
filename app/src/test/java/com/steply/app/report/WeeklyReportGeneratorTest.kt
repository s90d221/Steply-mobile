package com.steply.app.report

import com.steply.app.domain.model.AssessmentSex
import com.steply.app.domain.model.BalanceStage
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId
import com.steply.app.sync.SteplyAdherence
import com.steply.app.sync.SteplyAgentRationale
import com.steply.app.sync.SteplyLocalReportData
import com.steply.app.sync.SteplyDataProfile
import com.steply.app.sync.SteplyFallReport
import com.steply.app.sync.SteplyInvalidAttempts
import com.steply.app.sync.SteplyRecentAssessment
import com.steply.app.sync.SteplyReportRecommendation
import com.steply.app.sync.SteplyReportRecommendationStatus
import com.steply.app.sync.SteplySafetyEvent
import com.steply.app.sync.SteplyWeeklyReportSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportGeneratorTest {
    @Test
    fun `REQ-S5-REPORT maps only the strict typed weekly snapshot`() {
        val contract = contractFixture()

        val report = WeeklyReportGenerator.generate(contract)

        assertEquals("HIGH", report.latestRiskLevel)
        assertEquals("LOW", report.previousRiskLevel)
        assertTrue(report.riskChanged)
        assertEquals(listOf("V1", "V7"), report.weakAreas)
        assertEquals(listOf(12.0, 10.0), report.trends.single { it.label == "30 sec Chair Stand" }.values)
        assertEquals(listOf(9.5, 7.25), report.trends.single { it.label == "4-Stage Balance · TANDEM" }.values)
        assertEquals(2, report.adherence.completedSessions)
        assertEquals(3, report.adherence.targetSessions)
        assertEquals(listOf("DIZZINESS"), report.safetyEvents)
        assertEquals(1, report.fallHistory.reportedFallCount)
        assertTrue(report.fallHistory.injuriousFall)
        assertEquals(0.2, report.invalidAssessmentRatio, 0.0)
        assertEquals(2, report.recentAssessments.size)

        val formatted = WeeklyReportFormatter.format(report)
        assertTrue(formatted.contains("Risk change: LOW -> HIGH"))
        assertTrue(formatted.contains("ADVANCE_REASSESSMENT: DECLINING_TREND (SUCCEEDED)"))
        assertFalse(formatted.contains("profile-1"))
    }

    @Test
    fun `REQ-S10-3 one recent assessment is preserved even without a trend line`() {
        val original = contractFixture()
        val one = original.recentAssessments.takeLast(1)
        val contract = original.copy(
            recentAssessments = one,
            weeklyReport = original.weeklyReport.copy(recentAssessments = one),
        )

        val report = WeeklyReportGenerator.generate(contract)

        assertEquals(1, report.recentAssessments.size)
        assertTrue(report.trends.isEmpty())
        assertTrue(WeeklyReportFormatter.format(report).contains("chair 10"))
    }

    @Test
    fun `REQ-S10-3 all five bounded recent assessments remain in the report`() {
        val original = contractFixture()
        val seed = original.recentAssessments.last()
        val five = (1..5).map { index ->
            seed.copy(
                assessmentSessionId = "assessment-$index",
                completedAt = seed.completedAt + index,
                chairStandRepetitions = 9 + index,
            )
        }
        val contract = original.copy(
            recentAssessments = five,
            weeklyReport = original.weeklyReport.copy(recentAssessments = five),
        )

        val report = WeeklyReportGenerator.generate(contract)

        assertEquals(5, report.recentAssessments.size)
        assertEquals(listOf(10, 11, 12, 13, 14), report.recentAssessments.map { it.chairStandRepetitions })
    }

    private fun contractFixture(): SteplyLocalReportData {
        val generatedAt = 1_700_000_100_000L
        val recent = listOf(
            assessment("old", generatedAt - 5_000, SteadiRisk.LOW, 12, 9.5),
            assessment("new", generatedAt - 1_000, SteadiRisk.HIGH, 10, 7.25),
        )
        val weekly = SteplyWeeklyReportSnapshot(
            periodStart = generatedAt - 604_800_000L,
            periodEnd = generatedAt,
            generatedAt = generatedAt,
            latestRiskLevel = SteadiRisk.HIGH,
            previousRiskLevel = SteadiRisk.LOW,
            riskChanged = true,
            vulnerabilityIds = listOf(VulnerabilityId.V1, VulnerabilityId.V7),
            adherence = SteplyAdherence(2, 3),
            invalidAttempts = SteplyInvalidAttempts(1, 5, 0.2),
            safetyEvents = listOf(SteplySafetyEvent("safety-1", "DIZZINESS", generatedAt - 500)),
            fallReports = listOf(SteplyFallReport("fall-1", generatedAt - 400, true, true)),
            recommendation = SteplyReportRecommendation(SteplyReportRecommendationStatus.REASSESS, false, false),
            agentRationale = listOf(
                SteplyAgentRationale("ADVANCE_REASSESSMENT", listOf("DECLINING_TREND"), "SUCCEEDED", generatedAt - 300),
            ),
            recentAssessments = recent,
        )
        return SteplyLocalReportData(
            profile = SteplyDataProfile("profile-1", "Tester", 1950, AssessmentSex.FEMALE),
            recentAssessments = recent,
            weeklyReport = weekly,
            generatedAt = generatedAt,
        )
    }

    private fun assessment(
        id: String,
        completedAt: Long,
        risk: SteadiRisk,
        repetitions: Int,
        tandem: Double,
    ) = SteplyRecentAssessment(
        assessmentSessionId = id,
        completedAt = completedAt,
        risk = risk,
        vulnerabilityIds = emptyList(),
        chairStandRepetitions = repetitions,
        balanceSecondsByStage = linkedMapOf(
            BalanceStage.SIDE_BY_SIDE to 10.0,
            BalanceStage.SEMI_TANDEM to 10.0,
            BalanceStage.TANDEM to tandem,
            BalanceStage.ONE_LEG to 3.0,
        ),
    )
}
