package com.steply.app.ui.screens.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steply.app.AppContainer
import com.steply.app.domain.model.UserProfile
import com.steply.app.domain.model.ExerciseId
import com.steply.app.domain.model.WorkoutProgress
import com.steply.app.sync.SteplyWebClient
import com.steply.app.sync.SteplyWebSessionLink
import com.steply.app.sync.SteplyWebSessionPayload
import com.steply.app.ui.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

data class RemoteConnectUiState(
    val selectedProfile: UserProfile? = null,
    val manualQrValue: String = "",
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String = "Scan the QR code on your PC to link this profile and camera.",
    val openPersistedAssessment: Boolean = false,
    val latestExercisePlan: RecommendedExercisePlan? = null,
    val workoutProgress: WorkoutProgress? = null,
)

private data class RemoteConnectActionState(
    val manualQrValue: String = "",
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String = "Scan the QR code on your PC to link this profile and camera.",
    val openPersistedAssessment: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConnectViewModel(
    private val appContainer: AppContainer,
    private val webClient: SteplyWebClient = SteplyWebClient(),
) : ViewModel() {
    private val actionState = MutableStateFlow(RemoteConnectActionState())
    private val latestPlanState = MutableStateFlow<RecommendedExercisePlan?>(null)
    private val workoutState = MutableStateFlow<WorkoutProgress?>(null)

    private val baseUiState = combine(
        appContainer.userProfileRepository.observeActiveProfiles(),
        appContainer.settingsRepository.selectedUserId,
        actionState,
    ) { profiles, selectedUserId, action ->
        RemoteConnectUiState(
            selectedProfile = profiles.firstOrNull { it.id == selectedUserId },
            manualQrValue = action.manualQrValue,
            isConnecting = action.isConnecting,
            errorMessage = action.errorMessage,
            statusMessage = action.statusMessage,
            openPersistedAssessment = action.openPersistedAssessment,
        )
    }

    val uiState: StateFlow<RemoteConnectUiState> = combine(
        baseUiState,
        latestPlanState,
        workoutState,
    ) { base, plan, workout ->
        base.copy(latestExercisePlan = plan, workoutProgress = workout)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = RemoteConnectUiState(),
    )

    init {
        viewModelScope.launch {
            appContainer.settingsRepository.selectedUserId.flatMapLatest { profileId ->
                if (profileId == null) {
                    flowOf(null to null)
                } else {
                    appContainer.assessmentSessionRepository.observeByProfileId(profileId).map { sessions ->
                        val plan = sessions.asSequence()
                            .sortedByDescending { it.envelope.session.updatedAt }
                            .mapNotNull { persisted -> parseRecommendedExercisePlan(persisted.envelope.session) }
                            .firstOrNull()
                        profileId to plan
                    }
                }
            }.collectLatest { (profileId, plan) ->
                latestPlanState.value = plan
                workoutState.value = null
                if (profileId == null || plan == null || plan.exerciseStartBlocked || plan.exercises.isEmpty()) {
                    return@collectLatest
                }
                val workout = appContainer.workoutRepository.getOrCreateOpenWorkout(
                    profileId,
                    plan.planId,
                    plan.exercises.map { ExerciseId.valueOf(it.id) },
                )
                appContainer.workoutRepository.observeWorkout(workout.workoutSessionId).collect {
                    workoutState.value = it
                }
            }
        }
    }

    fun toggleMission(missionId: String) {
        val workout = workoutState.value ?: return
        val exerciseId = runCatching { ExerciseId.valueOf(missionId) }.getOrNull() ?: return
        viewModelScope.launch {
            appContainer.workoutRepository.setExerciseCompleted(
                workout.workoutSessionId,
                exerciseId,
                exerciseId !in workout.completedExerciseIds,
            )
        }
    }

    fun onManualQrChanged(value: String) {
        actionState.update {
            it.copy(
                manualQrValue = value,
                errorMessage = null,
            )
        }
    }

    fun connectManual() {
        connectFromQr(actionState.value.manualQrValue)
    }

    fun connectFromQr(rawValue: String) {
        val profile = uiState.value.selectedProfile
        if (profile == null) {
            actionState.update {
                it.copy(
                    errorMessage = "Select or create a profile first.",
                    isConnecting = false,
                )
            }
            return
        }

        val session = SteplyWebSessionLink.parse(rawValue)
        if (session == null) {
            actionState.update {
                it.copy(
                    errorMessage = "This QR code is invalid, expired, already used, or not encrypted. Refresh the HTTPS QR code on the PC and scan it again.",
                    isConnecting = false,
                )
            }
            return
        }

        actionState.update {
            it.copy(
                isConnecting = true,
                errorMessage = null,
                statusMessage = "Linking this profile to the PC session...",
            )
        }

        viewModelScope.launch {
            val prepared = runCatching {
                val contract = appContainer.steplyDataContractRepository.build(profile.id)
                appContainer.assessmentSessionRepository.createPending(session, profile) to contract
            }.getOrElse { error ->
                actionState.update {
                    it.copy(isConnecting = false, errorMessage = "Could not create assessment session: ${error.message}")
                }
                return@launch
            }
            val (pending, dataContract) = prepared
            webClient.connectProfile(
                session = session,
                assessmentSession = pending.envelope.session,
                dataContract = dataContract,
                callback = object : SteplyWebClient.ResultCallback {
                    override fun onSuccess(body: String, connectedSession: SteplyWebSessionPayload) {
                        SteplyWebSessionLink.markConsumed(connectedSession)
                        viewModelScope.launch {
                            runCatching {
                                val activated = appContainer.assessmentSessionRepository.activate(
                                    pending.envelope.session.assessmentSessionId,
                                    responseEnvelopeJson = body,
                                )
                                appContainer.careAgentEventIngestor.sessionStarted(
                                    profileId = activated.envelope.session.profileId,
                                    sourceEventId = connectedSession.sessionId,
                                    occurredAt = System.currentTimeMillis(),
                                )
                            }.onSuccess {
                                actionState.update {
                                    it.copy(
                                        isConnecting = false,
                                        statusMessage = "Connected to ${connectedSession.serverUrl}. Assessment state is saved on this phone.",
                                        openPersistedAssessment = true,
                                    )
                                }
                            }.onFailure { error ->
                                actionState.update {
                                    it.copy(
                                        isConnecting = false,
                                        errorMessage = "PC connected, but the assessment session could not be activated: ${error.message}",
                                    )
                                }
                            }
                        }
                    }

                    override fun onFailure(message: String) {
                        actionState.update {
                            it.copy(
                                isConnecting = false,
                                errorMessage = "Could not connect to the PC. Tried:\n$message",
                                statusMessage = "Check that both devices are on the same network and the PC server is running.",
                            )
                        }
                    }
                },
            )
        }
    }

    fun onLinkedSessionNavigationHandled() {
        actionState.update { it.copy(openPersistedAssessment = false) }
    }

    companion object {
        fun factory(appContainer: AppContainer) = viewModelFactory {
            RemoteConnectViewModel(appContainer)
        }
    }
}
