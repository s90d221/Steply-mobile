package com.steply.app.care.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

enum class AndroidCareWorkKind {
    REASSESSMENT,
    SESSION,
    REMINDER,
}

data class AndroidCareWorkRequest(
    val actionId: String,
    val eventId: String,
    val profileId: String,
    val kind: AndroidCareWorkKind,
    val scheduledAtMs: Long,
    val messageKey: String? = null,
)

data class AndroidCareWorkReceipt(
    val uniqueWorkName: String,
    val workRequestId: String,
)

/** Actual Android scheduler used by the care-agent tool adapter. */
class AndroidCareWorkScheduler(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun schedule(request: AndroidCareWorkRequest): AndroidCareWorkReceipt {
        require(request.actionId.isNotBlank())
        require(request.eventId.isNotBlank())
        require(request.profileId.isNotBlank())
        require(request.scheduledAtMs >= 0L)
        val uniqueName = uniqueWorkName(request)
        val delayMs = (request.scheduledAtMs - nowMs()).coerceAtLeast(0L)
        val inputData = Data.Builder()
            .putString(CareWorkKeys.ActionId, request.actionId)
            .putString(CareWorkKeys.EventId, request.eventId)
            .putString(CareWorkKeys.ProfileId, request.profileId)
            .putString(CareWorkKeys.Kind, request.kind.name)
            .putString(CareWorkKeys.MessageKey, request.messageKey)
            .build()
        val work = OneTimeWorkRequestBuilder<CareNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .addTag(uniqueName)
            .addTag(ProfileWorkTag.forProfile(request.profileId))
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, work)
        return AndroidCareWorkReceipt(uniqueName, work.id.toString())
    }

    companion object {
        fun uniqueWorkName(request: AndroidCareWorkRequest): String =
            "steply-care:${request.profileId}:${request.eventId}:${request.actionId}:${request.kind.name.lowercase()}"
    }
}

class CareNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val actionId = inputData.getString(CareWorkKeys.ActionId) ?: return Result.failure()
        val kind = inputData.getString(CareWorkKeys.Kind)
            ?.let { runCatching { AndroidCareWorkKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val messageKey = inputData.getString(CareWorkKeys.MessageKey)
            ?: CareNotificationTemplates.defaultMessageKey(kind)
        return when (AndroidCareNotifier(applicationContext).notify(actionId, messageKey)) {
            AndroidNotificationResult.POSTED,
            AndroidNotificationResult.PERMISSION_NOT_GRANTED,
            -> Result.success()
            AndroidNotificationResult.UNKNOWN_MESSAGE_KEY -> Result.failure()
        }
    }
}

internal object CareWorkKeys {
    const val ActionId = "care_action_id"
    const val EventId = "care_event_id"
    const val ProfileId = "care_profile_id"
    const val Kind = "care_work_kind"
    const val MessageKey = "care_message_key"
}
