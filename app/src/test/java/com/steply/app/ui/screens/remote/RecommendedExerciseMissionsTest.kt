package com.steply.app.ui.screens.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecommendedExerciseMissionsTest {
    @Test
    fun `REQ-S8-HIGH-APPROVAL blocked HIGH copy requires professional approval`() {
        val copy = blockedExercisePlanCopy(plan(professionalReviewRequired = true))

        assertEquals("Professional approval required", copy.title)
        assertFalse(copy.message.contains("Level A"))
    }

    @Test
    fun `REQ-S8-BLOCKED non-professional block reports no targeted exercise instead of approval`() {
        val copy = blockedExercisePlanCopy(plan(professionalReviewRequired = false))

        assertEquals("No targeted exercises available", copy.title)
        assertFalse(copy.message.contains("professional", ignoreCase = true))
    }

    private fun plan(professionalReviewRequired: Boolean) = RecommendedExercisePlan(
        profileId = "profile-1",
        planId = "plan-1",
        testLabel = "STEADI Assessment",
        recommendationLevel = "LOW",
        exercises = emptyList(),
        professionalReviewRequired = professionalReviewRequired,
        exerciseStartBlocked = true,
    )
}
