package com.steply.app.data.repository

import com.steply.app.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val settingsDataStore: SettingsDataStore,
) {
    val selectedUserId: Flow<String?> = settingsDataStore.selectedUserId

    suspend fun setSelectedUserId(userId: String?) {
        settingsDataStore.setSelectedUserId(userId)
    }

    suspend fun clearSelectedUserId() {
        settingsDataStore.clearSelectedUserId()
    }

}
