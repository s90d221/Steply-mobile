package com.steply.app.care

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.steply.app.data.local.database.SteplyDatabase
import com.steply.app.data.repository.CareAgentRepository
import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CareAgentRoomRestartTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun REQ_S4_RESTORE_fileCloseReopenRestoresStateDecisionReceiptAndDeduplicates() = runBlocking {
        var calls = 0
        val registry = object : CareToolRegistry {
            override fun tool(id: CareToolId) = CareTool {
                calls += 1
                CareToolResult(true, "ANDROID_TEST_OK")
            }
        }
        val event = event()
        val state = input()
        val firstDb = database()
        val firstRepository = CareAgentRepository(firstDb.careAgentDao())
        val perception = CarePerceptionSource { _, _ -> state }
        val first = CareAgentRunner(
            firstRepository,
            registry,
            clock = CareClock { 2_000L },
            perceptionSource = perception,
        ).run(event)
        assertEquals(1, calls)
        firstDb.close()

        val reopened = database()
        val reopenedRepository = CareAgentRepository(reopened.careAgentDao())
        assertNotNull(reopenedRepository.getState("profile-1"))
        assertNotNull(reopenedRepository.getDecisionLog(first.decisionId))
        assertEquals(1, reopened.careAgentDao().getActionReceipts(first.decisionId).size)

        val duplicate = CareAgentRunner(
            reopenedRepository,
            registry,
            clock = CareClock { 3_000L },
            perceptionSource = perception,
        ).run(event)
        assertTrue(duplicate.duplicateEvent)
        assertEquals(1, calls)
        reopened.close()
    }

    private fun database() = Room.databaseBuilder(context, SteplyDatabase::class.java, DatabaseName)
        .addMigrations(*SteplyDatabase.ALL_MIGRATIONS)
        .build()

    private fun event(): CareEvent {
        val type = CareEventType.MANUAL_REFRESH
        val source = "room-restart"
        return CareEvent(
            CareStableIds.eventId("profile-1", type, source),
            "profile-1",
            type,
            source,
            2_000L,
        )
    }

    private fun input() = CareInputState(
        profile = CareProfileSnapshot("profile-1", 1950, "FEMALE", 100L),
        canonicalClinicalReference = CareCanonicalClinicalReference(
            assessmentSessionId = "assessment-1",
            assessmentRevision = 1L,
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
        reassessmentDueAt = 99_000L,
        nextPlannedSessionAt = null,
        progressionEligible = false,
        caregiverNotificationsConsented = false,
        perceivedAt = 2_000L,
    )

    private companion object {
        const val DatabaseName = "care-agent-restart-test.db"
    }
}
