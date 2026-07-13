package com.steply.app.care.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCareAdaptersTest {
    @Test
    fun `S4-TOOL-01 scheduler unique name includes profile event action and work kind`() {
        val request = AndroidCareWorkRequest(
            actionId = "action-1",
            eventId = "event-1",
            profileId = "profile-1",
            kind = AndroidCareWorkKind.REASSESSMENT,
            scheduledAtMs = 10L,
        )

        assertEquals(
            "steply-care:profile-1:event-1:action-1:reassessment",
            AndroidCareWorkScheduler.uniqueWorkName(request),
        )
        assertEquals(
            AndroidCareWorkScheduler.uniqueWorkName(request),
            AndroidCareWorkScheduler.uniqueWorkName(request.copy(scheduledAtMs = 20L)),
        )
    }

    @Test
    fun `S4-GR-05 notifier templates reject unapproved message keys`() {
        assertEquals(null, CareNotificationTemplates.copy("agent.free.text"))
        val plannerTemplateIds = listOf(
            CareNotificationTemplates.SafetyStopAndReview,
            CareNotificationTemplates.FallMedicalReview,
            CareNotificationTemplates.ProfessionalReviewRequired,
            CareNotificationTemplates.CaregiverProfessionalReview,
            CareNotificationTemplates.CaregiverDecliningTrend,
            CareNotificationTemplates.CaregiverLowAdherence,
            CareNotificationTemplates.ProgressionApprovalRequest,
        )
        assertTrue(plannerTemplateIds.all { CareNotificationTemplates.copy(it) != null })
    }
}
