package com.steply.app.care

import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.AssessmentSessionStatus
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.UserProfile
import com.steply.app.sync.assessmentFixture
import com.steply.app.sync.scoredFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareInputStateBuilderTest {
    @Test
    fun `S4-STATE-01 Room perception input keeps five valid results trend adherence and invalid counts`() {
        val baseTime = 1_700_000_000_000L
        val sessions = (1..6).map { index ->
            val fixture = assessmentFixture(
                revision = index.toLong(),
                sessionStatus = AssessmentSessionStatus.COMPLETED,
                chairRepetitions = 16 - index,
                tandemSeconds = 10.0 - index,
                steadi = scoredFixture(SteadiRisk.LOW),
            ).session
            fixture.copy(
                assessmentSessionId = "assessment-$index",
                createdAt = baseTime + index,
                updatedAt = baseTime + index * 1_000L,
                completedAt = baseTime + index * 1_000L,
            )
        }.toMutableList()
        val first = sessions.first()
        val chair = first.functionalTests.chairStand30s
        sessions[0] = first.copy(
            functionalTests = first.functionalTests.copy(
                chairStand30s = chair.copy(
                    attempts = chair.attempts + chair.attempts.single().copy(
                        attemptId = "invalid-attempt",
                        status = AssessmentAttemptStatus.INVALID,
                    ),
                ),
            ),
        )
        val profile = UserProfile(
            id = "profile-1",
            displayName = "Profile",
            birthYear = 1950,
            gender = "FEMALE",
            heightCm = 160,
            movementNotes = null,
            safetyNote = null,
            createdAt = baseTime,
            updatedAt = baseTime,
            archivedAt = null,
        )
        val eventType = CareEventType.SESSION_START
        val event = CareEvent(
            eventId = CareStableIds.eventId(profile.id, eventType, "session-start"),
            profileId = profile.id,
            type = eventType,
            sourceEventId = "session-start",
            occurredAt = baseTime + 10_000L,
        )

        val state = CareInputStateBuilder().build(
            profile = profile,
            sessions = sessions,
            prior = null,
            event = event,
            perceivedAt = baseTime + 10_000L,
        )

        assertEquals(listOf("assessment-2", "assessment-3", "assessment-4", "assessment-5", "assessment-6"), state.recentAssessments.map { it.assessmentSessionId })
        assertTrue(state.recentAssessments.all { it.valid })
        assertTrue(state.trend.declining)
        assertEquals(5, state.trend.consecutiveDeclines)
        assertEquals(listOf(0, 0), state.adherence.completedSessionsByWeek)
        assertEquals(2, state.adherence.consecutiveLowWeeks)
        assertEquals(1, state.invalidAttemptNumerator)
        assertEquals(13, state.invalidAttemptDenominator)
        assertEquals(1.0 / 13.0, state.invalidAttemptRatio, 1e-12)
        assertEquals("assessment-6", state.canonicalClinicalReference.assessmentSessionId)
        assertEquals(baseTime + 6_000L + CareAgentConfigV1.value.reassessmentIntervalMs, state.reassessmentDueAt)
    }
}
