package com.steply.app.report

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.steply.app.AppContainer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val selectedProfileId = container.settingsRepository.selectedUserId.first()
        val report = WeeklyReportGenerator.generate(
            items = container.movementHistoryRepository.getAll(),
            selectedProfileId = selectedProfileId,
        ) ?: return Result.success(workDataOf(ReportGenerated to false))

        val reportText = WeeklyReportFormatter.format(report)
        WeeklyReportStore.save(applicationContext, reportText, report.generatedAtMs)
        WeeklyReportNotifier.showReady(applicationContext)

        return Result.success(
            workDataOf(
                ReportGenerated to true,
                ReportGeneratedAt to report.generatedAtMs,
                ReportSourceCount to report.sourceCount,
            ),
        )
    }

    companion object {
        const val ReportGenerated = "report_generated"
        const val ReportGeneratedAt = "report_generated_at"
        const val ReportSourceCount = "report_source_count"
    }
}

object WeeklyReportWorkScheduler {
    private const val PeriodicWorkName = "steply_weekly_report_generation"
    private const val ImmediateWorkName = "steply_weekly_report_generation_now"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
            .addTag(PeriodicWorkName)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PeriodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun triggerNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WeeklyReportWorker>()
            .addTag(ImmediateWorkName)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ImmediateWorkName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

object WeeklyReportStore {
    private const val PreferencesName = "steply_weekly_report"
    private const val LastReportText = "last_report_text"
    private const val LastReportGeneratedAt = "last_report_generated_at"

    fun save(
        context: Context,
        reportText: String,
        generatedAtMs: Long,
    ) {
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LastReportText, reportText)
            .putLong(LastReportGeneratedAt, generatedAtMs)
            .apply()
    }

    fun latest(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(LastReportText, null)
    }
}
