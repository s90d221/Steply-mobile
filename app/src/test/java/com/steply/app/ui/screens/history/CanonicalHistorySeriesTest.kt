package com.steply.app.ui.screens.history

import com.steply.app.domain.model.AssessmentSummary
import com.steply.app.domain.model.BalanceStage
import com.steply.app.domain.model.SteadiRisk
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalHistorySeriesTest {
    @Test
    fun `REQ-S10-1 all four balance stages preserve exact double values for recent five`() {
        val summaries = (1..6).map { index ->
            AssessmentSummary(
                assessmentSessionId = "assessment-$index",
                profileId = "profile-1",
                completedAt = index.toLong(),
                risk = SteadiRisk.LOW,
                vulnerabilityIds = emptyList(),
                chairStandRepetitions = 10 + index,
                balanceSecondsByStage = linkedMapOf(
                    BalanceStage.SIDE_BY_SIDE to index + 0.1,
                    BalanceStage.SEMI_TANDEM to index + 0.2,
                    BalanceStage.TANDEM to index + 0.3,
                    BalanceStage.ONE_LEG to index + 0.4,
                ),
                valid = true,
            )
        }

        val series = canonicalBalanceSeries(summaries)

        assertEquals(BalanceStage.entries, series.map { it.stage })
        assertEquals(listOf(2.1, 3.1, 4.1, 5.1, 6.1), series[0].values)
        assertEquals(listOf(2.2, 3.2, 4.2, 5.2, 6.2), series[1].values)
        assertEquals(listOf(2.3, 3.3, 4.3, 5.3, 6.3), series[2].values)
        assertEquals(listOf(2.4, 3.4, 4.4, 5.4, 6.4), series[3].values)
    }
}
