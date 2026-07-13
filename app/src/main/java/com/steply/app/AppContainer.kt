package com.steply.app

import android.content.Context
import com.steply.app.care.CareAgentRunner
import com.steply.app.care.RoomCarePerceptionSource
import com.steply.app.care.android.AndroidCareToolRegistry
import com.steply.app.care.android.CareAgentEventDispatcher
import com.steply.app.care.android.CareAgentEventIngestor
import com.steply.app.care.android.RoomCareProgressStoreTool
import com.steply.app.data.local.SettingsDataStore
import com.steply.app.data.local.database.SteplyDatabase
import com.steply.app.data.repository.AssessmentSessionRepository
import com.steply.app.data.repository.CareAgentRepository
import com.steply.app.data.repository.SettingsRepository
import com.steply.app.data.repository.UserProfileRepository
import com.steply.app.data.repository.AssessmentSummaryRepository
import com.steply.app.data.repository.LandmarkSeriesRepository
import com.steply.app.data.repository.ProfileDataRepository
import com.steply.app.data.repository.ProfileDeletionCoordinator
import com.steply.app.data.repository.SteplyDataContractRepository
import com.steply.app.data.repository.WorkoutRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = SteplyDatabase.getInstance(appContext)
    private val settingsDataStore = SettingsDataStore(appContext)

    val settingsRepository = SettingsRepository(settingsDataStore)
    val userProfileRepository = UserProfileRepository(database.userProfileDao())
    val assessmentSummaryRepository = AssessmentSummaryRepository(database.assessmentSummaryDao())
    val workoutRepository = WorkoutRepository(database.workoutDao())
    val profileDataRepository = ProfileDataRepository(database.profileDataDao())
    val profileDeletionCoordinator = ProfileDeletionCoordinator(
        appContext,
        profileDataRepository,
        settingsRepository,
    )
    val assessmentSessionRepository = AssessmentSessionRepository(
        database.assessmentSessionDao(),
        database.assessmentSummaryDao(),
    )
    val landmarkSeriesRepository = LandmarkSeriesRepository(database.landmarkSeriesDao(), database.assessmentSessionDao())
    val careAgentRepository = CareAgentRepository(database.careAgentDao())
    val steplyDataContractRepository = SteplyDataContractRepository(
        userProfileRepository,
        assessmentSessionRepository,
        workoutRepository,
        careAgentRepository,
    )
    val carePerceptionSource = RoomCarePerceptionSource(
        userProfiles = userProfileRepository,
        assessments = assessmentSessionRepository,
        careStates = careAgentRepository,
    )
    val careToolRegistry = AndroidCareToolRegistry(
        context = appContext,
        reportDataProvider = steplyDataContractRepository::buildWeeklyReport,
        progressStoreTool = RoomCareProgressStoreTool(careAgentRepository),
    )
    val careAgentRunner = CareAgentRunner(
        repository = careAgentRepository,
        tools = careToolRegistry,
        perceptionSource = carePerceptionSource,
    )
    val careAgentEventIngestor = CareAgentEventIngestor(CareAgentEventDispatcher(appContext))
}
