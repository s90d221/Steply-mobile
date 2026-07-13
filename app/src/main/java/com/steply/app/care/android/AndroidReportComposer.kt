package com.steply.app.care.android

import com.steply.app.report.WeeklyReport
import com.steply.app.report.WeeklyReportFormatter
import com.steply.app.report.WeeklyReportGenerator
import com.steply.app.sync.SteplyLocalReportData

data class AndroidComposedReport(
    val report: WeeklyReport,
    val text: String,
)

/** Template-only report composer; clinical values are copied from persisted snapshots. */
object AndroidReportComposer {
    fun compose(
        contract: SteplyLocalReportData,
    ): AndroidComposedReport {
        val report = WeeklyReportGenerator.generate(contract)
        return AndroidComposedReport(report, WeeklyReportFormatter.format(report))
    }
}
