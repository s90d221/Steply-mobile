package com.steply.app.care

import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareAgentContractCodecTest {
    @Test
    fun `REQ-S4-CONTRACT state event action tool result projection and update preserve exact schema versions`() {
        val input = inputFixture()
        assertEquals(input, CareStateJsonCodec.decodeInput(CareStateJsonCodec.encodeInput(input)))

        val event = eventFixture()
        assertEquals(event, CareStateJsonCodec.decodeEvent(CareStateJsonCodec.encodeEvent(event)))

        val action = actionFixture(event)
        val actionJson = CareStateJsonCodec.encodeCandidates(listOf(action))
        assertEquals(listOf(action), CareStateJsonCodec.decodeCandidates(actionJson))
        assertEquals(CARE_AGENT_ACTION_SCHEMA_VERSION, JSONArray(actionJson).getJSONObject(0).getString("schemaVersion"))

        val executionJson = CareStateJsonCodec.encodeExecutions(
            listOf(CareActionExecution(action.actionId, action.toolId, CareExecutionStatus.SUCCEEDED, CareToolResult(true, "OK"))),
        )
        assertEquals(
            CARE_AGENT_TOOL_RESULT_SCHEMA_VERSION,
            JSONArray(executionJson).getJSONObject(0).getString("schemaVersion"),
        )

        val state = CareAgentState(
            profileId = "profile-1",
            stateVersion = 8L,
            input = input,
            latestDecisionId = "decision-1",
            updatedAt = 9_000L,
        )
        val summary = CareDecisionSummary("decision-1", CareDecisionBranch.MAINTENANCE, listOf(action), 8_000L)
        val projection = CareAgentProjectionFactory.createFromSummary(state, summary)
        val decodedProjection = CareAgentProjectionJsonCodec.decodeProjection(
            CareAgentProjectionJsonCodec.encodeProjection(projection),
        )
        assertEquals(projection, decodedProjection)

        val update = CareAgentProjectionFactory.update(0L, projection)
        val decodedUpdate = CareAgentProjectionJsonCodec.decodeUpdate(CareAgentProjectionJsonCodec.encodeUpdate(update))
        assertEquals(update, decodedUpdate)
        assertEquals("care-agent.updated", decodedUpdate.type)
        assertEquals("care_agent_projection.v1", decodedUpdate.projection.schemaVersion)
    }

    @Test
    fun `REQ-S4-CONTRACT strict codecs reject unknown fields and unknown wire enums`() {
        val inputJson = JSONObject(CareStateJsonCodec.encodeInput(inputFixture())).put("unknown", true)
        assertFalse(runCatching { CareStateJsonCodec.decodeInput(inputJson.toString()) }.isSuccess)

        val eventJson = JSONObject(CareStateJsonCodec.encodeEvent(eventFixture())).put("riskLevel", "LOW")
        assertFalse(runCatching { CareStateJsonCodec.decodeEvent(eventJson.toString()) }.isSuccess)

        val actionArray = JSONArray(CareStateJsonCodec.encodeCandidates(listOf(actionFixture(eventFixture()))))
        actionArray.getJSONObject(0).put("branch", "UNKNOWN_BRANCH")
        assertFalse(runCatching { CareStateJsonCodec.decodeCandidates(actionArray.toString()) }.isSuccess)

        val projection = JSONObject(
            """{
              "schemaVersion":"care_agent_projection.v1",
              "profileId":"profile-1",
              "stateVersion":1,
              "currentSessionPlan":null,
              "nextReassessmentAt":null,
              "latestDecision":null,
              "updatedAt":1,
              "unknown":true
            }""".trimIndent(),
        )
        assertFalse(runCatching { CareAgentProjectionJsonCodec.decodeProjection(projection.toString()) }.isSuccess)
    }

    @Test
    fun `REQ-S4-INVALID numerator denominator and exact ratio survive restart codec`() {
        val input = inputFixture().copy(
            invalidAttemptNumerator = 2,
            invalidAttemptDenominator = 5,
            invalidAttemptRatio = 0.4,
        )
        val decoded = CareStateJsonCodec.decodeInput(CareStateJsonCodec.encodeInput(input))
        assertEquals(2, decoded.invalidAttemptNumerator)
        assertEquals(5, decoded.invalidAttemptDenominator)
        assertEquals(0.4, decoded.invalidAttemptRatio, 0.0)
        assertTrue(CarePlanner().plan(eventFixture(), decoded).evaluations.isNotEmpty())
    }

    private fun inputFixture() = CareInputState(
        profile = CareProfileSnapshot("profile-1", 1950, "FEMALE", 100L),
        canonicalClinicalReference = CareCanonicalClinicalReference(
            assessmentSessionId = "assessment-1",
            assessmentRevision = 7L,
            steadiRuleVersion = "steadi_stage1.v1",
            risk = SteadiRisk.LOW,
            vulnerabilityRuleVersion = "stage2_vulnerability.v1",
            vulnerabilityIds = emptySet(),
            prescriptionPlanId = "plan-1",
            prescriptionSchemaVersion = "otago_prescription.v1",
            professionalApprovalStatus = ApprovalStatus.NOT_REQUIRED,
            professionalApprovalId = null,
        ),
        recentAssessments = listOf(CareAssessmentSummary("assessment-1", 1_000L, 12, 10.0, true)),
        trend = CareTrendSnapshot(false, 0),
        adherence = CareAdherenceSnapshot(listOf(3, 3), 3, 0),
        safetyEvents = emptyList(),
        fallReports = emptyList(),
        invalidAttemptNumerator = 0,
        invalidAttemptDenominator = 4,
        invalidAttemptRatio = 0.0,
        reassessmentDueAt = 100_000L,
        nextPlannedSessionAt = 20_000L,
        progressionEligible = false,
        caregiverNotificationsConsented = false,
        perceivedAt = 10_000L,
    )

    private fun eventFixture(): CareEvent {
        val type = CareEventType.MANUAL_REFRESH
        val source = "manual-1"
        return CareEvent(
            eventId = CareStableIds.eventId("profile-1", type, source),
            profileId = "profile-1",
            type = type,
            sourceEventId = source,
            occurredAt = 10_000L,
        )
    }

    private fun actionFixture(event: CareEvent): CareActionCandidate {
        val key = CareStableIds.causalActionKey(
            event.profileId,
            CareActionType.MAINTAIN_PLAN,
            "care_plan",
            event.eventId,
        )
        return CareActionCandidate(
            actionId = key,
            idempotencyKey = key,
            eventId = event.eventId,
            profileId = event.profileId,
            branch = CareDecisionBranch.MAINTENANCE,
            actionType = CareActionType.MAINTAIN_PLAN,
            toolId = CareToolId.PROGRESS_STORE,
            target = "care_plan",
            reasonCodes = listOf("NO_HIGHER_PRIORITY_SIGNAL"),
            payload = CareActionPayload(),
        )
    }
}
