package com.steply.app.data.repository

import android.content.Context
import androidx.work.WorkManager
import com.steply.app.care.android.ProfileWorkTag
import com.steply.app.report.WeeklyReportStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates irreversible deletion across Room and profile-scoped Android stores. */
class ProfileDeletionCoordinator(
    context: Context,
    private val profileDataRepository: ProfileDataRepository,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    private val appContext = context.applicationContext

    suspend fun deleteProfile(profileId: String) {
        require(profileId.isNotBlank())
        profileDataRepository.purgeProfile(profileId)
        WeeklyReportStore.clear(appContext, profileId)
        withContext(Dispatchers.IO) {
            workManager.cancelAllWorkByTag(ProfileWorkTag.forProfile(profileId)).result.get()
        }
        if (settingsRepository.selectedUserId.first() == profileId) {
            settingsRepository.clearSelectedUserId()
        }
    }
}
