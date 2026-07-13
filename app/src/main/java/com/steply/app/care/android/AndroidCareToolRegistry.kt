package com.steply.app.care.android

import android.content.Context
import com.steply.app.care.CareActionType
import com.steply.app.care.CareTool
import com.steply.app.care.CareToolId
import com.steply.app.care.CareToolRegistry
import com.steply.app.care.CareToolRequest
import com.steply.app.care.CareToolResult
import com.steply.app.report.WeeklyReportStore
import com.steply.app.sync.SteplyLocalReportData

/**
 * Production Android tools. The progress-store tool is supplied by the Room layer;
 * this adapter does not keep an in-memory fallback.
 */
class AndroidCareToolRegistry(
    context: Context,
    private val reportDataProvider: suspend (profileId: String) -> SteplyLocalReportData,
    private val progressStoreTool: CareTool,
    private val clock: () -> Long = System::currentTimeMillis,
) : CareToolRegistry {
    private val appContext = context.applicationContext
    private val scheduler = AndroidCareWorkScheduler(appContext, nowMs = clock)
    private val notifier = AndroidCareNotifier(appContext)

    override fun tool(id: CareToolId): CareTool = when (id) {
        CareToolId.SCHEDULER -> CareTool(::schedule)
        CareToolId.NOTIFIER -> CareTool(::notify)
        CareToolId.REPORT_COMPOSER -> CareTool(::composeReport)
        CareToolId.PROGRESS_STORE -> progressStoreTool
    }

    private suspend fun schedule(request: CareToolRequest): CareToolResult {
        val kind = when (request.actionType) {
            CareActionType.REQUEST_IMMEDIATE_REASSESSMENT,
            CareActionType.ADVANCE_REASSESSMENT,
            CareActionType.SCHEDULE_DUE_REASSESSMENT,
            -> AndroidCareWorkKind.REASSESSMENT
            CareActionType.ADJUST_REMINDER -> AndroidCareWorkKind.REMINDER
            CareActionType.PROPOSE_SPLIT_SESSION,
            CareActionType.MAINTAIN_PLAN,
            CareActionType.SCHEDULE_SESSION,
            -> AndroidCareWorkKind.SESSION
            CareActionType.COMPOSE_WEEKLY_REPORT ->
                return CareToolResult(false, "REPORT_MUST_USE_COMPOSER_TOOL", retryable = false)
            else -> return CareToolResult(false, "UNSUPPORTED_SCHEDULER_ACTION", retryable = false)
        }
        val scheduledAt = request.payload.scheduledAtMs ?: when (request.actionType) {
            CareActionType.REQUEST_IMMEDIATE_REASSESSMENT,
            CareActionType.ADJUST_REMINDER,
            -> clock()
            else -> return CareToolResult(false, "SCHEDULED_AT_REQUIRED", retryable = false)
        }
        return runCatching {
            scheduler.schedule(
                AndroidCareWorkRequest(
                    actionId = request.actionId,
                    eventId = request.eventId,
                    profileId = request.profileId,
                    kind = kind,
                    scheduledAtMs = scheduledAt,
                    messageKey = request.payload.messageTemplateId,
                ),
            )
        }.fold(
            onSuccess = { CareToolResult(true, "WORK_ENQUEUED", it.uniqueWorkName) },
            onFailure = { CareToolResult(false, "WORK_ENQUEUE_FAILED", retryable = true) },
        )
    }

    private suspend fun notify(request: CareToolRequest): CareToolResult {
        if (request.actionType == CareActionType.NOTIFY_CONSENTED_CAREGIVER && request.payload.recipientId.isNullOrBlank()) {
            return CareToolResult(false, "RECIPIENT_REQUIRED", retryable = false)
        }
        val messageKey = request.payload.messageTemplateId ?: defaultMessageKey(request.actionType)
            ?: return CareToolResult(false, "MESSAGE_TEMPLATE_REQUIRED", retryable = false)
        return when (notifier.notify(request.actionId, messageKey)) {
            AndroidNotificationResult.POSTED -> CareToolResult(true, "NOTIFICATION_POSTED", request.actionId)
            AndroidNotificationResult.PERMISSION_NOT_GRANTED ->
                CareToolResult(false, "NOTIFICATION_PERMISSION_NOT_GRANTED", retryable = false)
            AndroidNotificationResult.UNKNOWN_MESSAGE_KEY ->
                CareToolResult(false, "UNAPPROVED_MESSAGE_TEMPLATE", retryable = false)
        }
    }

    private suspend fun composeReport(request: CareToolRequest): CareToolResult {
        if (request.actionType != CareActionType.COMPOSE_WEEKLY_REPORT) {
            return CareToolResult(false, "UNSUPPORTED_REPORT_ACTION", retryable = false)
        }
        return runCatching {
            val composed = AndroidReportComposer.compose(
                contract = reportDataProvider(request.profileId),
            )
            WeeklyReportStore.save(
                appContext,
                request.profileId,
                composed.text,
                composed.report.generatedAtMs,
            )
            CareToolResult(true, "REPORT_COMPOSED", "weekly-report:${request.profileId}:${composed.report.generatedAtMs}")
        }.getOrElse {
            CareToolResult(false, "REPORT_COMPOSITION_FAILED", retryable = true)
        }
    }

    private fun defaultMessageKey(actionType: CareActionType): String? = when (actionType) {
        CareActionType.STOP_SESSION,
        CareActionType.RECOMMEND_MEDICAL_REVIEW,
        CareActionType.REQUIRE_PROFESSIONAL_REVIEW,
        -> CareNotificationTemplates.SafetyReview
        CareActionType.PROPOSE_PROGRESSION -> CareNotificationTemplates.ProgressionReview
        CareActionType.NOTIFY_CONSENTED_CAREGIVER -> CareNotificationTemplates.SafetyReview
        else -> null
    }
}
