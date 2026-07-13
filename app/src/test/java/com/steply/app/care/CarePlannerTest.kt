package com.steply.app.care

import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePlannerTest {
    private val planner = CarePlanner()

    @Test
    fun `S4-PRIORITY-01 complete decision tree selects exact highest branch`() {
        val cases = listOf(
            CareDecisionBranch.SAFETY_EVENT to baseState().copy(
                safetyEvents = listOf(CareSafetyEventSnapshot("safety-1", "DIZZINESS", 900L, true)),
            ),
            CareDecisionBranch.FALL_REPORTED to baseState().copy(
                fallReports = listOf(CareFallReportSnapshot("fall-1", 900L, false, true)),
            ),
            CareDecisionBranch.HIGH_OR_V6_V7 to baseState().withClinical(risk = SteadiRisk.HIGH),
            CareDecisionBranch.DECLINING_TREND to baseState().copy(
                trend = CareTrendSnapshot(declining = true, consecutiveDeclines = 3),
            ),
            CareDecisionBranch.REASSESSMENT_DUE to baseState().copy(reassessmentDueAt = 1_000L),
            CareDecisionBranch.LOW_ADHERENCE to baseState().copy(
                adherence = CareAdherenceSnapshot(listOf(1, 1), 3, 2),
            ),
            CareDecisionBranch.PROGRESSION_AVAILABLE to baseState().copy(progressionEligible = true),
            CareDecisionBranch.MAINTENANCE to baseState(),
        )

        cases.forEachIndexed { index, (expected, state) ->
            val event = event("branch-$index")
            val result = planner.plan(event, state)
            assertEquals(expected, result.selectedBranch)
            assertTrue(result.selectedActions.isNotEmpty())
            assertTrue(result.evaluations.all { it.checks.size == 8 })
        }
    }

    @Test
    fun `S4-TREE-01 safety suppresses every lower priority signal`() {
        val state = baseState()
            .copy(
                safetyEvents = listOf(CareSafetyEventSnapshot("safety-1", "CHEST_PAIN", 900L, true)),
                fallReports = listOf(CareFallReportSnapshot("fall-1", 900L, true, true)),
                trend = CareTrendSnapshot(true, 5),
                reassessmentDueAt = 0L,
                adherence = CareAdherenceSnapshot(listOf(0, 0), 3, 2),
                progressionEligible = true,
            )
            .withClinical(SteadiRisk.HIGH, setOf(VulnerabilityId.V6, VulnerabilityId.V7))

        val result = planner.plan(event("all-signals"), state)

        assertEquals(CareDecisionBranch.SAFETY_EVENT, result.selectedBranch)
        assertTrue(result.selectedActions.all { it.branch == CareDecisionBranch.SAFETY_EVENT })
        assertTrue(result.candidateDecisions.any { it.disposition == CareCandidateDisposition.REJECTED_BY_GUARDRAIL })
    }

    @Test
    fun `S4-GR-01 clinical risk vulnerability prescription approval writes and free text are rejected`() {
        val state = baseState()
        val event = event("guardrail")
        val key = CareStableIds.causalActionKey(
            event.profileId,
            CareActionType.MAINTAIN_PLAN,
            "care_plan",
            event.eventId,
        )
        val candidate = CareActionCandidate(
            actionId = key,
            idempotencyKey = key,
            eventId = event.eventId,
            profileId = event.profileId,
            branch = CareDecisionBranch.MAINTENANCE,
            actionType = CareActionType.MAINTAIN_PLAN,
            toolId = CareToolId.NOTIFIER,
            target = "care_plan",
            reasonCodes = listOf("UNTRUSTED_PROPOSAL"),
            payload = CareActionPayload(
                parameters = mapOf(
                    "riskLevel" to "LOW",
                    "vulnerabilityIds" to "V1",
                    "plan" to "replacement",
                    "professionalApproval" to "APPROVED",
                    "message" to "free text",
                ),
            ),
        )

        val evaluation = CareGuardrail().evaluate(candidate, state)

        assertFalse(evaluation.allowed)
        assertFalse(evaluation.checks.first { it.guardrailId == "GR1" }.passed)
        assertFalse(evaluation.checks.first { it.guardrailId == "GR3" }.passed)
        assertFalse(evaluation.checks.first { it.guardrailId == "GR7" }.passed)
    }

    @Test
    fun `REQ-S6-M03 weekly report action is stable within a week and rotates next week`() {
        val interval = CareAgentConfigV1.value.weeklyReportIntervalMs
        fun wake(source: String, occurredAt: Long) = CareEvent(
            eventId = CareStableIds.eventId("profile-1", CareEventType.SCHEDULED_WAKEUP, source),
            profileId = "profile-1",
            type = CareEventType.SCHEDULED_WAKEUP,
            sourceEventId = source,
            occurredAt = occurredAt,
        )
        fun reportAction(source: String, perceivedAt: Long) = planner.plan(
            wake(source, perceivedAt),
            baseState().copy(perceivedAt = perceivedAt, reassessmentDueAt = Long.MAX_VALUE),
        ).selectedActions.single { it.actionType == CareActionType.COMPOSE_WEEKLY_REPORT }

        val first = reportAction("wake-1", interval + 1L)
        val sameWeek = reportAction("wake-2", interval + 1_000L)
        val nextWeek = reportAction("wake-3", interval * 2L + 1L)

        assertEquals(CareToolId.REPORT_COMPOSER, first.toolId)
        assertEquals(first.idempotencyKey, sameWeek.idempotencyKey)
        assertTrue(first.idempotencyKey != nextWeek.idempotencyKey)
    }

    private fun baseState(): CareInputState = CareInputState(
        profile = CareProfileSnapshot("profile-1", 1950, "FEMALE", 100L),
        canonicalClinicalReference = CareCanonicalClinicalReference(
            assessmentSessionId = "assessment-1",
            assessmentRevision = 4L,
            steadiRuleVersion = "steadi_stage1.v1",
            risk = SteadiRisk.LOW,
            vulnerabilityRuleVersion = "stage2_vulnerability.v1",
            vulnerabilityIds = emptySet(),
            prescriptionPlanId = "plan-1",
            prescriptionSchemaVersion = "otago_prescription.v1",
            professionalApprovalStatus = ApprovalStatus.NOT_REQUIRED,
            professionalApprovalId = null,
        ),
        recentAssessments = emptyList(),
        trend = CareTrendSnapshot(false, 0),
        adherence = CareAdherenceSnapshot(listOf(3, 3), 3, 0),
        safetyEvents = emptyList(),
        fallReports = emptyList(),
        invalidAttemptNumerator = 0,
        invalidAttemptDenominator = 0,
        invalidAttemptRatio = 0.0,
        reassessmentDueAt = 2_000L,
        nextPlannedSessionAt = null,
        progressionEligible = false,
        caregiverNotificationsConsented = false,
        perceivedAt = 1_000L,
    )

    private fun CareInputState.withClinical(
        risk: SteadiRisk,
        vulnerabilities: Set<VulnerabilityId> = emptySet(),
    ) = copy(
        canonicalClinicalReference = canonicalClinicalReference.copy(
            risk = risk,
            vulnerabilityIds = vulnerabilities,
            professionalApprovalStatus = if (risk == SteadiRisk.HIGH) ApprovalStatus.PENDING else ApprovalStatus.NOT_REQUIRED,
        ),
    )

    private fun event(source: String): CareEvent {
        val type = CareEventType.SESSION_START
        return CareEvent(
            eventId = CareStableIds.eventId("profile-1", type, source),
            profileId = "profile-1",
            type = type,
            sourceEventId = source,
            occurredAt = 1_000L,
        )
    }
}
