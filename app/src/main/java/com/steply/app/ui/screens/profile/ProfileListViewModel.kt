package com.steply.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steply.app.AppContainer
import com.steply.app.domain.model.UserProfile
import com.steply.app.ui.text.SteplyCopy
import com.steply.app.ui.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileListUiState(
    val profiles: List<UserProfile> = emptyList(),
    val selectedUserId: String? = null,
    val archiveTarget: UserProfile? = null,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
)

private data class ProfileListActionState(
    val archiveTarget: UserProfile? = null,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
)

class ProfileListViewModel(
    private val appContainer: AppContainer,
) : ViewModel() {
    private val actionState = MutableStateFlow(ProfileListActionState())

    val uiState: StateFlow<ProfileListUiState> = combine(
        appContainer.userProfileRepository.observeActiveProfiles(),
        appContainer.settingsRepository.selectedUserId,
        actionState,
    ) { profiles, selectedUserId, action ->
        ProfileListUiState(
            profiles = profiles,
            selectedUserId = selectedUserId,
            archiveTarget = action.archiveTarget,
            isWorking = action.isWorking,
            errorMessage = action.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProfileListUiState(),
    )

    fun selectProfile(userId: String, onSelected: () -> Unit) {
        viewModelScope.launch {
            appContainer.settingsRepository.setSelectedUserId(userId)
            onSelected()
        }
    }

    fun requestArchive(profile: UserProfile) {
        actionState.update {
            it.copy(archiveTarget = profile, errorMessage = null)
        }
    }

    fun dismissArchiveDialog() {
        actionState.update { it.copy(archiveTarget = null) }
    }

    fun confirmArchive() {
        val target = actionState.value.archiveTarget ?: return
        if (actionState.value.isWorking) return

        viewModelScope.launch {
            actionState.update { it.copy(isWorking = true, errorMessage = null) }
            runCatching {
                appContainer.profileDeletionCoordinator.deleteProfile(target.id)
            }.onSuccess {
                actionState.value = ProfileListActionState()
            }.onFailure {
                actionState.update {
                    it.copy(
                        isWorking = false,
                        errorMessage = SteplyCopy.GenericError,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(appContainer: AppContainer) = viewModelFactory {
            ProfileListViewModel(appContainer)
        }
    }
}
