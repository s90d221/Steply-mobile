package com.steply.app.report

import android.content.Context

/** Durable cache for reports composed through the Care Agent report tool or explicit user sharing. */
object WeeklyReportStore {
    private const val PreferencesName = "steply_weekly_report"
    private const val LastReportText = "last_report_text:"
    private const val LastReportGeneratedAt = "last_report_generated_at:"

    fun save(
        context: Context,
        profileId: String,
        reportText: String,
        generatedAtMs: Long,
    ) {
        require(profileId.isNotBlank())
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LastReportText + profileId, reportText)
            .putLong(LastReportGeneratedAt + profileId, generatedAtMs)
            .apply()
    }

    fun latest(context: Context, profileId: String): String? {
        require(profileId.isNotBlank())
        return context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(LastReportText + profileId, null)
    }

    fun clear(context: Context, profileId: String) {
        require(profileId.isNotBlank())
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(LastReportText + profileId)
            .remove(LastReportGeneratedAt + profileId)
            .commit()
            .also { check(it) { "Failed to durably delete the profile weekly report" } }
    }
}
