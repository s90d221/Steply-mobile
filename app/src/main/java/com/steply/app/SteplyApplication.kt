package com.steply.app

import android.app.Application
import com.steply.app.care.android.CareAgentCycleScheduler

class SteplyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CareAgentCycleScheduler.schedule(this)
    }
}
