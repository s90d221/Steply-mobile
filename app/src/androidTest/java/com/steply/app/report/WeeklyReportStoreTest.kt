package com.steply.app.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeeklyReportStoreTest {
    @Test
    fun REQ_S5_REPORT_STORE_profileReportIsolationSurvivesClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WeeklyReportStore.save(context, "profile-a", "report-a", 1)
        WeeklyReportStore.save(context, "profile-b", "report-b", 2)

        WeeklyReportStore.clear(context, "profile-a")

        assertNull(WeeklyReportStore.latest(context, "profile-a"))
        assertEquals("report-b", WeeklyReportStore.latest(context, "profile-b"))
        WeeklyReportStore.clear(context, "profile-b")
    }
}
