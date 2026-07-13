package com.steply.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.steplySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "steply_settings",
)

class SettingsDataStore(context: Context) {
    private val dataStore = context.steplySettingsDataStore

    val selectedUserId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[SELECTED_USER_ID]
    }

    suspend fun setSelectedUserId(userId: String?) {
        dataStore.edit { preferences ->
            if (userId == null) {
                preferences.remove(SELECTED_USER_ID)
            } else {
                preferences[SELECTED_USER_ID] = userId
            }
        }
    }

    suspend fun clearSelectedUserId() {
        setSelectedUserId(null)
    }

    private companion object {
        val SELECTED_USER_ID = stringPreferencesKey("selected_user_id")
    }
}
