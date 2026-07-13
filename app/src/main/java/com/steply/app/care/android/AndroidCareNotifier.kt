package com.steply.app.care.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.steply.app.MainActivity
import com.steply.app.R

enum class AndroidNotificationResult {
    POSTED,
    PERMISSION_NOT_GRANTED,
    UNKNOWN_MESSAGE_KEY,
}

data class CareNotificationCopy(val title: String, val body: String)

/** Pre-reviewed copy only. Neither the agent nor an optional LLM can add messages. */
object CareNotificationTemplates {
    const val ReassessmentDue = "care.reassessment.due"
    const val SessionDue = "care.session.due"
    const val ReminderDue = "care.reminder.due"
    const val SafetyReview = "care.safety.review"
    const val ProgressionReview = "care.progression.review"
    const val SafetyStopAndReview = "care_safety_stop_and_review"
    const val FallMedicalReview = "care_fall_medical_review"
    const val ProfessionalReviewRequired = "care_professional_review_required"
    const val CaregiverProfessionalReview = "care_caregiver_professional_review"
    const val CaregiverDecliningTrend = "care_caregiver_declining_trend"
    const val CaregiverLowAdherence = "care_caregiver_low_adherence"
    const val ProgressionApprovalRequest = "care_progression_approval_request"

    private val copies = mapOf(
        ReassessmentDue to CareNotificationCopy(
            "Steply reassessment due",
            "Open Steply to complete the scheduled reassessment.",
        ),
        SessionDue to CareNotificationCopy(
            "Steply session ready",
            "Open Steply to review today's approved exercise session.",
        ),
        ReminderDue to CareNotificationCopy(
            "Steply reminder",
            "Your planned Steply activity is ready when you are.",
        ),
        SafetyReview to CareNotificationCopy(
            "Steply safety review",
            "Exercise remains paused. Review the recorded safety guidance.",
        ),
        ProgressionReview to CareNotificationCopy(
            "Progression approval requested",
            "Review the deterministic progression proposal before applying it.",
        ),
        SafetyStopAndReview to CareNotificationCopy(
            "Exercise stopped for safety",
            "Remain in a safe position and review the approved medical-contact guidance.",
        ),
        FallMedicalReview to CareNotificationCopy(
            "Fall follow-up required",
            "Complete the immediate reassessment and contact a medical professional.",
        ),
        ProfessionalReviewRequired to CareNotificationCopy(
            "Professional review required",
            "Exercise progression remains on hold pending professional review.",
        ),
        CaregiverProfessionalReview to CareNotificationCopy(
            "Steply professional review notice",
            "The participant's approved care plan requires professional review.",
        ),
        CaregiverDecliningTrend to CareNotificationCopy(
            "Steply reassessment moved earlier",
            "A declining stored score trend moved the participant's reassessment earlier.",
        ),
        CaregiverLowAdherence to CareNotificationCopy(
            "Steply adherence support",
            "The participant may benefit from the approved reminder and split-session options.",
        ),
        ProgressionApprovalRequest to CareNotificationCopy(
            "Progression approval requested",
            "Review the deterministic progression proposal before approving any change.",
        ),
    )

    fun copy(messageKey: String): CareNotificationCopy? = copies[messageKey]

    fun defaultMessageKey(kind: AndroidCareWorkKind): String = when (kind) {
        AndroidCareWorkKind.REASSESSMENT -> ReassessmentDue
        AndroidCareWorkKind.SESSION -> SessionDue
        AndroidCareWorkKind.REMINDER -> ReminderDue
    }
}

class AndroidCareNotifier(private val context: Context) {
    fun notify(actionId: String, messageKey: String): AndroidNotificationResult {
        require(actionId.isNotBlank())
        val copy = CareNotificationTemplates.copy(messageKey)
            ?: return AndroidNotificationResult.UNKNOWN_MESSAGE_KEY
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return AndroidNotificationResult.PERMISSION_NOT_GRANTED
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ChannelId, "Care reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Scheduled Steply reassessment, session, and approved care reminders."
                },
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            actionId.hashCode(),
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(appContext, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(actionId.hashCode(), notification)
        return AndroidNotificationResult.POSTED
    }

    companion object {
        const val ChannelId = "care_agent_actions"
    }
}
