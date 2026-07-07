package com.steply.app

import android.app.Application
import com.steply.app.report.WeeklyReportWorkScheduler

class SteplyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WeeklyReportWorkScheduler.schedule(this)
    }
}
