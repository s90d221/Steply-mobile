package com.steply.app.sync

import com.steply.app.domain.model.AssessmentSessionStatus
import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.AssessmentResultStatus
import com.steply.app.domain.model.AssessmentSlotStatus
import com.steply.app.domain.model.BalanceFailureCode
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.UserProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SteplyDataContractTest {
    @Test
    fun `REQ-S5-X01 connect payload is exact minimized shape and preserves CDC arm-use zero`() {
        val session = assessmentFixture(
            revision = 2,
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            armUse = true,
            steadi = scoredFixture(SteadiRisk.HIGH),
        ).session
        val contract = SteplyDataContractBuilder.build(profile(), listOf(session), generatedAt = 1_700_000_010_000L)

        val json = SteplyDataContractJsonCodec.encodeObject(contract)

        assertEquals(setOf("schemaVersion", "profile", "recentAssessments", "generatedAt"), json.keys().asSequence().toSet())
        assertEquals(setOf("id", "displayName", "birthYear", "sex"), json.getJSONObject("profile").keys().asSequence().toSet())
        assertEquals(0, json.getJSONArray("recentAssessments").getJSONObject(0).getInt("chairStandRepetitions"))
        assertFalse(json.has("weeklyReport"))
        assertFalse(json.toString().contains("safetyEvents"))
        assertFalse(json.toString().contains("agentRationale"))
    }

    @Test
    fun `REQ-S5-X02 codec rejects weekly report and profile updatedAt extras`() {
        val valid = SteplyDataContractJsonCodec.encodeObject(
            SteplyDataContractBuilder.build(profile(), emptyList(), generatedAt = 1_700_000_010_000L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SteplyDataContractJsonCodec.decode(JSONObject(valid.toString()).put("weeklyReport", JSONObject()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val extraProfile = JSONObject(valid.toString())
            extraProfile.getJSONObject("profile").put("updatedAt", 1L)
            SteplyDataContractJsonCodec.decode(extraProfile)
        }
    }

    @Test
    fun `REQ-S10-3 recent assessments are bounded to five valid aggregates`() {
        val seed = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(),
        ).session
        val sessions = (1..6).map { index ->
            seed.copy(
                assessmentSessionId = "assessment-$index",
                revision = index.toLong(),
                completedAt = seed.completedAt!! + index,
            )
        }

        val contract = SteplyDataContractBuilder.build(profile(), sessions, generatedAt = 1_700_000_010_000L)

        assertEquals(5, contract.recentAssessments.size)
        assertEquals(listOf("assessment-2", "assessment-3", "assessment-4", "assessment-5", "assessment-6"), contract.recentAssessments.map { it.assessmentSessionId })
        assertTrue(contract.recentAssessments.all { it.valid })
    }

    @Test
    fun `REQ-S10-3 local report retains safety from an invalid non-scored attempt`() {
        val seed = assessmentFixture().session
        val balanceSlot = seed.functionalTests.fourStageBalance
        val invalidResult = balanceSlot.acceptedResult!!.copy(
            status = AssessmentResultStatus.INVALID,
            quality = qualityFixture(0.21, listOf("G3_OVER_20_PERCENT"), true),
            balance = balanceSlot.acceptedResult.balance!!.copy(
                stages = balanceSlot.acceptedResult.balance.stages.mapIndexed { index, stage ->
                    if (index == 0) stage.copy(failureCode = BalanceFailureCode.F1, failureReason = "STEP") else stage
                },
            ),
        )
        val invalidAttempt = balanceSlot.attempts.single().copy(
            status = AssessmentAttemptStatus.INVALID,
            result = invalidResult,
        )
        val invalidSession = seed.copy(
            functionalTests = seed.functionalTests.copy(
                fourStageBalance = balanceSlot.copy(
                    status = AssessmentSlotStatus.NEEDS_RETRY,
                    acceptedAttemptId = null,
                    acceptedResult = null,
                    attempts = listOf(invalidAttempt),
                ),
            ),
        )

        val report = SteplyDataContractBuilder.buildLocalReport(
            profile = profile(),
            sessions = listOf(invalidSession),
            workouts = emptyList(),
            careState = null,
            decisions = emptyList(),
            generatedAt = 1_700_000_010_000L,
        ).weeklyReport

        assertEquals(1, report.invalidAttempts.numerator)
        assertTrue(report.safetyEvents.any { it.type == "F1" })
    }

    private fun profile() = UserProfile(
        id = "profile-1",
        displayName = "Tester",
        birthYear = 1950,
        gender = "FEMALE",
        heightCm = null,
        movementNotes = "must not transfer",
        safetyNote = "must not transfer",
        createdAt = 1,
        updatedAt = 2,
        archivedAt = null,
    )
}
