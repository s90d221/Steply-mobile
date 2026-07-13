package com.steply.app.care.android

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidCareToolIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun S4_TOOL_01_schedulerUsesUniqueWorkAndKeepForDuplicateAction() {
        val manager = WorkManager.getInstance(context)
        val request = AndroidCareWorkRequest(
            actionId = "integration-action-1",
            eventId = "integration-event-1",
            profileId = "integration-profile-1",
            kind = AndroidCareWorkKind.REASSESSMENT,
            scheduledAtMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7),
        )
        val uniqueName = AndroidCareWorkScheduler.uniqueWorkName(request)
        manager.cancelUniqueWork(uniqueName).result.get(10, TimeUnit.SECONDS)

        val scheduler = AndroidCareWorkScheduler(context, manager)
        scheduler.schedule(request)
        scheduler.schedule(request)

        val work = manager.getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertTrue(work.single().tags.contains(uniqueName))
        manager.cancelUniqueWork(uniqueName).result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun S4_TOOL_02_notifierPostsOnlyApprovedTemplateThroughNotificationManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        val actionId = "integration-notification-action"
        val notifier = AndroidCareNotifier(context)

        assertEquals(
            AndroidNotificationResult.UNKNOWN_MESSAGE_KEY,
            notifier.notify("unknown-action", "free_generated_text"),
        )
        assertEquals(
            AndroidNotificationResult.POSTED,
            notifier.notify(actionId, CareNotificationTemplates.SafetyStopAndReview),
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = (0 until 20).any {
            if (manager.activeNotifications.any { notification -> notification.id == actionId.hashCode() }) {
                true
            } else {
                Thread.sleep(100L)
                false
            }
        }
        assertTrue(posted)
        manager.cancel(actionId.hashCode())
    }
}
