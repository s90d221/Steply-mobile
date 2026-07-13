package com.steply.app.care.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.steply.app.SteplyApplication
import com.steply.app.care.CareEvent
import com.steply.app.care.CareEventType
import com.steply.app.care.CareExecutionStatus
import com.steply.app.care.CareAgentConfigV1
import com.steply.app.care.CareStableIds
import com.steply.app.care.CareStateJsonCodec
import com.steply.app.care.CarePerceptionNotReadyException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class CareAgentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawEvent = inputData.getString(EventJsonKey) ?: return Result.failure()
        val event = runCatching { CareStateJsonCodec.decodeEvent(rawEvent) }.getOrElse {
            return Result.failure()
        }
        val application = applicationContext as? SteplyApplication ?: return Result.failure()
        val result = try {
            application.container.careAgentRunner.run(event)
        } catch (_: CarePerceptionNotReadyException) {
            return Result.success()
        } catch (_: Throwable) {
            return Result.retry()
        }
        val retryable = result.decision?.executions?.any {
            it.status == CareExecutionStatus.FAILED_RETRYABLE
        } == true
        return if (retryable) Result.retry() else Result.success()
    }

    companion object {
        const val EventJsonKey = "care_agent_event_json"
    }
}

class CareAgentCycleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? SteplyApplication ?: return Result.failure()
        val profileId = application.container.settingsRepository.selectedUserId.first() ?: return Result.success()
        val now = System.currentTimeMillis()
        val intervalMs = TimeUnit.HOURS.toMillis(CareAgentConfigV1.value.agentCycleIntervalHours)
        val sourceEventId = "periodic:${now / intervalMs}"
        val type = CareEventType.SCHEDULED_WAKEUP
        val event = CareEvent(
            eventId = CareStableIds.eventId(profileId, type, sourceEventId),
            profileId = profileId,
            type = type,
            sourceEventId = sourceEventId,
            occurredAt = now,
        )
        return try {
            val run = application.container.careAgentRunner.run(event)
            if (run.decision?.executions?.any { it.status == CareExecutionStatus.FAILED_RETRYABLE } == true) {
                Result.retry()
            } else Result.success()
        } catch (_: CarePerceptionNotReadyException) {
            // The assessment-updated entry point will enqueue an immediate cycle.
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}

object CareAgentCycleScheduler {
    private const val WorkName = "steply_care_agent_periodic_cycle"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CareAgentCycleWorker>(
            CareAgentConfigV1.value.agentCycleIntervalHours,
            TimeUnit.HOURS,
        ).addTag(WorkName).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class CareAgentEventDispatcher(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun enqueue(event: CareEvent) {
        val workName = uniqueWorkName(event)
        val request = OneTimeWorkRequestBuilder<CareAgentWorker>()
            .setInputData(Data.Builder().putString(CareAgentWorker.EventJsonKey, CareStateJsonCodec.encodeEvent(event)).build())
            .addTag(workName)
            .addTag(ProfileWorkTag.forProfile(event.profileId))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        fun uniqueWorkName(event: CareEvent) = "steply-care-agent-event:${event.eventId}"
    }
}

/** Public ingestion boundary. Clinical values are never accepted as event payload. */
class CareAgentEventIngestor(
    private val dispatcher: CareAgentEventDispatcher,
) {
    fun sessionStarted(profileId: String, sourceEventId: String, occurredAt: Long): CareEvent =
        enqueue(profileId, CareEventType.SESSION_START, sourceEventId, occurredAt, emptyMap())

    fun assessmentUpdated(
        profileId: String,
        assessmentSessionId: String,
        revision: Long,
        occurredAt: Long,
    ): CareEvent = enqueue(
        profileId,
        CareEventType.ASSESSMENT_UPDATED,
        "$assessmentSessionId:$revision",
        occurredAt,
        emptyMap(),
    )

    fun safetyEvent(profileId: String, sourceEventId: String, safetyType: String, occurredAt: Long): CareEvent =
        enqueue(profileId, CareEventType.SAFETY_EVENT, sourceEventId, occurredAt, mapOf("safetyType" to safetyType))

    fun fallReported(profileId: String, sourceEventId: String, injurious: Boolean, occurredAt: Long): CareEvent =
        enqueue(profileId, CareEventType.FALL_REPORTED, sourceEventId, occurredAt, mapOf("injurious" to injurious.toString()))

    private fun enqueue(
        profileId: String,
        type: CareEventType,
        sourceEventId: String,
        occurredAt: Long,
        payload: Map<String, String>,
    ): CareEvent {
        require(profileId.isNotBlank() && sourceEventId.isNotBlank() && occurredAt >= 0L)
        val event = CareEvent(
            eventId = CareStableIds.eventId(profileId, type, sourceEventId),
            profileId = profileId,
            type = type,
            sourceEventId = sourceEventId,
            occurredAt = occurredAt,
            payload = payload,
        )
        dispatcher.enqueue(event)
        return event
    }
}
