package com.steply.app.care

import com.steply.app.data.local.dao.CareAgentDao
import com.steply.app.data.local.entities.CareActionReceiptEntity
import com.steply.app.data.local.entities.CareAgentStateEntity
import com.steply.app.data.local.entities.CareDecisionLogEntity
import com.steply.app.data.local.entities.CareEventEntity
import com.steply.app.data.repository.CareAgentRepository
import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareAgentRunnerTest {
    @Test
    fun `REQ-S4-DEDUP same unresolved safety signal across different wakeups executes external actions once`() = runBlocking {
        val dao = FakeCareAgentDao()
        val repository = CareAgentRepository(dao)
        var calls = 0
        val registry = object : CareToolRegistry {
            override fun tool(id: CareToolId) = CareTool {
                calls += 1
                CareToolResult(true, "OK")
            }
        }
        val safetySignal = CareSafetyEventSnapshot("causal-safety-1", "DIZZINESS", 1_000L, active = true)
        val state = maintenanceState().copy(safetyEvents = listOf(safetySignal))
        val firstEvent = event("wake-1", CareEventType.SCHEDULED_WAKEUP)
        val secondEvent = event("wake-2", CareEventType.SCHEDULED_WAKEUP)

        val first = CareAgentRunner(repository, registry, clock = CareClock { 2_000L }).run(firstEvent, state)
        val firstCalls = calls
        val second = CareAgentRunner(repository, registry, clock = CareClock { 3_000L }).run(secondEvent, state.copy(perceivedAt = 3_000L))

        assertEquals(3, firstCalls)
        assertEquals(firstCalls, calls)
        assertTrue(second.decision?.executions?.all { it.status == CareExecutionStatus.SKIPPED_DUPLICATE } == true)
        assertEquals(
            first.decision?.selectedActions?.map { it.idempotencyKey },
            second.decision?.selectedActions?.map { it.idempotencyKey },
        )
    }

    @Test
    fun `REQ-S4-TOOL-FAIL scheduler notifier report and progress failures preserve canonical clinical reference`() = runBlocking {
        val cases = listOf(
            CareToolId.SCHEDULER to maintenanceState().copy(nextPlannedSessionAt = 5_000L),
            CareToolId.NOTIFIER to maintenanceState().copy(
                canonicalClinicalReference = maintenanceState().canonicalClinicalReference.copy(
                    risk = SteadiRisk.HIGH,
                    professionalApprovalStatus = ApprovalStatus.PENDING,
                ),
            ),
            CareToolId.REPORT_COMPOSER to maintenanceState(),
            CareToolId.PROGRESS_STORE to maintenanceState(),
        )
        cases.forEachIndexed { index, (failedTool, state) ->
            val dao = FakeCareAgentDao()
            val repository = CareAgentRepository(dao)
            val registry = object : CareToolRegistry {
                override fun tool(id: CareToolId) = CareTool {
                    if (id == failedTool) CareToolResult(false, "INJECTED_${id.name}_FAILURE", retryable = true)
                    else CareToolResult(true, "OK")
                }
            }
            val type = if (failedTool == CareToolId.REPORT_COMPOSER) CareEventType.SCHEDULED_WAKEUP else CareEventType.SESSION_START
            val event = event("tool-$index", type)

            val result = CareAgentRunner(repository, registry, clock = CareClock { 2_000L }).run(event, state)

            assertTrue(result.decision?.executions?.any { it.toolId == failedTool && it.status == CareExecutionStatus.FAILED_RETRYABLE } == true)
            assertEquals(
                state.canonicalClinicalReference,
                repository.getState("profile-1")?.input?.canonicalClinicalReference,
            )
        }
    }

    @Test
    fun `S4-IDEMP-02 tool failure resumes after process restart and duplicate event is a no-op`() = runBlocking {
        val dao = FakeCareAgentDao()
        val repository = CareAgentRepository(dao)
        var toolCalls = 0
        val registry = object : CareToolRegistry {
            override fun tool(id: CareToolId) = CareTool {
                toolCalls += 1
                if (toolCalls == 1) {
                    CareToolResult(false, "INJECTED_FAILURE", retryable = true)
                } else {
                    CareToolResult(true, "EXECUTED", resultReference = "work-action")
                }
            }
        }
        val clock = CareClock { 2_000L + toolCalls }
        val state = maintenanceState()
        val event = event("restart-event")

        val first = CareAgentRunner(repository, registry, clock = clock).run(event, state)
        assertEquals(CareDecisionStatus.PARTIAL_FAILURE, first.decision?.status)
        assertEquals(1, toolCalls)
        assertEquals(SteadiRisk.LOW, repository.getState("profile-1")?.input?.canonicalClinicalReference?.risk)

        // Simulates a fresh process using the same durable DAO state.
        val secondRepository = CareAgentRepository(dao)
        val second = CareAgentRunner(secondRepository, registry, clock = clock).run(event, state.copy(perceivedAt = 9_999L))
        assertEquals(CareDecisionStatus.COMPLETED, second.decision?.status)
        assertEquals(2, toolCalls)
        assertEquals(state.canonicalClinicalReference, second.decision?.observedState?.canonicalClinicalReference)
        assertEquals(state.perceivedAt, second.decision?.observedState?.perceivedAt)

        val third = CareAgentRunner(CareAgentRepository(dao), registry, clock = clock).run(event, state)
        assertTrue(third.duplicateEvent)
        assertEquals(2, toolCalls)

        val actionId = requireNotNull(second.decision).selectedActions.single().actionId
        val receipt = requireNotNull(dao.getActionReceipt(actionId))
        assertEquals(2, JSONArray(receipt.attemptResultsJson).length())
        assertEquals(CareExecutionStatus.SUCCEEDED.name, receipt.status)

        val log = requireNotNull(secondRepository.getDecisionLog(second.decisionId))
        assertTrue(JSONArray(log.candidateActionsJson).length() >= 1)
        assertTrue(JSONArray(log.guardrailResultsJson).length() >= 1)
        assertTrue(JSONArray(log.candidateDecisionsJson).length() >= 1)
        assertTrue(JSONArray(log.executionResultsJson).length() >= 1)
        assertNotNull(repository.getState("profile-1"))
    }

    @Test
    fun `S4-IDEMP-01 reused event id with changed payload is rejected`() = runBlocking {
        val repository = CareAgentRepository(FakeCareAgentDao())
        val type = CareEventType.MANUAL_REFRESH
        val stableId = CareStableIds.eventId("profile-1", type, "source-1")
        val first = CareEvent(stableId, "profile-1", type, "source-1", 1_000L, mapOf("a" to "1"))
        val changed = first.copy(payload = mapOf("a" to "2"))

        repository.claimEvent(first, 1_000L)
        val result = runCatching { repository.claimEvent(changed, 1_001L) }

        assertFalse(result.isSuccess)
    }

    private fun maintenanceState() = CareInputState(
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
        invalidAttemptNumerator = 1,
        invalidAttemptDenominator = 10,
        invalidAttemptRatio = 0.1,
        reassessmentDueAt = 99_000L,
        nextPlannedSessionAt = null,
        progressionEligible = false,
        caregiverNotificationsConsented = false,
        perceivedAt = 2_000L,
    )

    private fun event(source: String, type: CareEventType = CareEventType.SESSION_START): CareEvent {
        return CareEvent(
            CareStableIds.eventId("profile-1", type, source),
            "profile-1",
            type,
            source,
            2_000L,
        )
    }
}

private class FakeCareAgentDao : CareAgentDao {
    private val states = linkedMapOf<String, CareAgentStateEntity>()
    private val stateFlows = linkedMapOf<String, MutableStateFlow<CareAgentStateEntity?>>()
    private val events = linkedMapOf<String, CareEventEntity>()
    private val decisions = linkedMapOf<String, CareDecisionLogEntity>()
    private val actions = linkedMapOf<String, CareActionReceiptEntity>()

    override suspend fun getState(profileId: String) = states[profileId]
    override fun observeState(profileId: String): Flow<CareAgentStateEntity?> =
        stateFlows.getOrPut(profileId) { MutableStateFlow(states[profileId]) }
    override suspend fun upsertState(state: CareAgentStateEntity) {
        states[state.profileId] = state
        stateFlows.getOrPut(state.profileId) { MutableStateFlow(null) }.value = state
    }

    override suspend fun insertEvent(event: CareEventEntity): Long =
        if (events.putIfAbsent(event.eventId, event) == null) events.size.toLong() else -1L
    override suspend fun getEvent(eventId: String) = events[eventId]
    override suspend fun updateEventStatus(eventId: String, status: String, updatedAt: Long) {
        events[eventId]?.let { events[eventId] = it.copy(status = status, updatedAt = updatedAt) }
    }

    override suspend fun upsertDecision(decision: CareDecisionLogEntity) {
        decisions[decision.decisionId] = decision
    }
    override suspend fun getDecisionByEvent(eventId: String) = decisions.values.firstOrNull { it.eventId == eventId }
    override suspend fun getDecision(decisionId: String) = decisions[decisionId]
    override suspend fun getDecisions(profileId: String) = decisions.values.filter { it.profileId == profileId }
    override fun observeLatestDecision(profileId: String): Flow<CareDecisionLogEntity?> =
        MutableStateFlow(decisions.values.filter { it.profileId == profileId }.maxByOrNull { it.createdAt })

    override suspend fun insertActionReceipt(receipt: CareActionReceiptEntity): Long =
        if (actions.putIfAbsent(receipt.actionId, receipt) == null) actions.size.toLong() else -1L
    override suspend fun updateActionReceipt(receipt: CareActionReceiptEntity) {
        actions[receipt.actionId] = receipt
    }
    override suspend fun getActionReceipt(actionId: String) = actions[actionId]
    override suspend fun getActionReceipts(decisionId: String) = actions.values.filter { it.decisionId == decisionId }
}
