package com.steply.app.domain.steadi

import com.steply.app.domain.model.AssessmentFallCount
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.sync.assessmentFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class SteadiScorerTest {
    @Test
    fun `full step1 by step2 by fall history decision table matches section 5_3`() {
        val falls = listOf(
            AssessmentFallCount.ZERO to false,
            AssessmentFallCount.ONE to false,
            AssessmentFallCount.ONE to true,
            AssessmentFallCount.TWO_OR_MORE to false,
        )
        for (answers in 0 until 8) {
            val q1 = answers and 1 != 0
            val q2 = answers and 2 != 0
            val q3 = answers and 4 != 0
            val step1 = q1 || q2 || q3
            for (chairProblem in listOf(false, true)) {
                for (balanceProblem in listOf(false, true)) {
                    val step2 = chairProblem || balanceProblem
                    for ((fallCount, injurious) in falls) {
                        val result = SteadiScorer.score(
                            assessmentFixture(
                                q1 = q1,
                                q2 = q2,
                                q3 = q3,
                                fallCount = fallCount,
                                injuriousFall = injurious,
                                chairRepetitions = if (chairProblem) 11 else 12,
                                tandemSeconds = if (balanceProblem) 9.9 else 10.0,
                            ).session,
                        )
                        val expected = when {
                            !step1 || !step2 -> SteadiRisk.LOW
                            injurious == true || fallCount == AssessmentFallCount.TWO_OR_MORE -> SteadiRisk.HIGH
                            else -> SteadiRisk.MODERATE
                        }
                        assertEquals("answers=$answers chair=$chairProblem balance=$balanceProblem fall=$fallCount", expected, result.risk)
                    }
                }
            }
        }
    }

    @Test
    fun `one missing functional test is not scorable`() {
        val result = SteadiScorer.score(assessmentFixture(balanceCompleted = false).session)

        assertEquals(SteadiRisk.NOT_SCORABLE, result.risk)
    }

    @Test
    fun `chair and tandem clinical boundaries are exact`() {
        val atBoundary = SteadiScorer.score(assessmentFixture(chairRepetitions = 12, tandemSeconds = 10.0).session)
        val belowBoundary = SteadiScorer.score(assessmentFixture(chairRepetitions = 11, tandemSeconds = 9.999).session)

        assertEquals(false, atBoundary.strengthProblem)
        assertEquals(false, atBoundary.balanceProblem)
        assertEquals(true, belowBoundary.strengthProblem)
        assertEquals(true, belowBoundary.balanceProblem)
    }
}
