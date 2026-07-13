package com.steply.app.ui.screens.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.steply.app.AppContainer
import com.steply.app.care.CareAgentProjectionFactory
import com.steply.app.care.CareAgentProjectionJsonCodec
import com.steply.app.data.repository.AssessmentUpdateResult
import com.steply.app.domain.model.hasScoredAggregate
import com.steply.app.domain.model.ExerciseId
import com.steply.app.domain.model.WorkoutProgress
import com.steply.app.sync.AssessmentSessionJsonCodec
import com.steply.app.sync.LandmarkSeriesJsonCodec
import com.steply.app.remote.RemoteCameraStreamer
import com.steply.app.sync.CleanupCallback
import com.steply.app.sync.PcSessionCleanupRequester
import com.steply.app.sync.PcCleanupGate
import com.steply.app.sync.SteplyWebClient
import com.steply.app.sync.SteplyWebSessionPayload
import com.steply.app.ui.screens.components.StatusChip
import com.steply.app.ui.screens.components.SteplyCard
import com.steply.app.ui.screens.components.SteplyDestructiveButton
import com.steply.app.ui.screens.components.SteplyPrimaryButton
import com.steply.app.ui.screens.components.SteplyScaffold
import com.steply.app.ui.screens.components.SteplyScreenColumn
import com.steply.app.ui.screens.components.SteplySecondaryButton
import com.steply.app.ui.screens.components.formatRecommendationLevelLabel
import kotlinx.coroutines.launch

@Composable
fun RemoteCameraScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onChangeProfile: () -> Unit,
    onViewHistory: () -> Unit,
) {
    val activeAssessment by appContainer.assessmentSessionRepository.observeActive()
        .collectAsStateWithLifecycle(initialValue = null)
    val persistedAssessment = activeAssessment
    if (persistedAssessment == null) {
        SteplyScaffold(
            title = "Assessment session",
            subtitle = "No active assessment is available on this phone.",
            onBack = onBack,
        ) { paddingValues ->
            SteplyScreenColumn(paddingValues = paddingValues) {
                SteplyCard {
                    Text(
                        text = "Scan a new PC QR code to start or resume an assessment.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        return
    }
    val session = persistedAssessment.connection
    val assessmentEnvelope = persistedAssessment.envelope
    val currentAssessmentEnvelope by rememberUpdatedState(assessmentEnvelope)
    val careState by appContainer.careAgentRepository.observeState(assessmentEnvelope.session.profileId)
        .collectAsStateWithLifecycle(initialValue = null)
    val latestCareDecision by appContainer.careAgentRepository.observeLatestDecisionSummary(assessmentEnvelope.session.profileId)
        .collectAsStateWithLifecycle(initialValue = null)
    val careProjection = careState?.let { state ->
        CareAgentProjectionFactory.createFromSummary(state, latestCareDecision)
    }
    val currentCareProjection by rememberUpdatedState(careProjection)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cleanupRequester: PcSessionCleanupRequester = remember { SteplyWebClient() }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    var streamer by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf<RemoteCameraStreamer?>(null) }
    var streaming by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf(false) }
    var statusMessage by remember(session.webSocketUrl, session.tlsCertSha256) {
        mutableStateOf("Ready to stream camera frames to the PC.")
    }
    var cameraMessage by remember { mutableStateOf("Checking camera permission.") }
    var confirmedFrames by remember { mutableIntStateOf(0) }
    var savedHistoryCount by remember { mutableIntStateOf(0) }
    var latestExercisePlan by remember { mutableStateOf(parseRecommendedExercisePlan(assessmentEnvelope.session)) }
    var finalResultReceived by remember { mutableStateOf(assessmentEnvelope.session.hasScoredAggregate()) }
    var resultSaved by remember { mutableStateOf(assessmentEnvelope.session.hasScoredAggregate()) }
    var showExerciseMissions by remember { mutableStateOf(false) }
    var workoutProgress by remember { mutableStateOf<WorkoutProgress?>(null) }
    val cleanupGate = remember(session.sessionId, session.serverUrl) { PcCleanupGate() }
    val pendingLandmarkPayloads = remember(session.sessionId) { mutableListOf<String>() }
    val streamerOwner = remember(session.webSocketUrl, session.tlsCertSha256) {
        CameraStreamerOwner<RemoteCameraStreamer>()
    }
    val autoStartGate = remember(session.sessionId, assessmentEnvelope.session.assessmentSessionId) {
        CameraAutoStartGate()
    }
    val previewAckTracker = remember(session.sessionId, assessmentEnvelope.session.assessmentSessionId) {
        CameraPreviewAckTracker()
    }

    LaunchedEffect(careState?.stateVersion) {
        streamer?.sendLatestCareAgentProjection()
    }
    LaunchedEffect(latestExercisePlan?.planId) {
        val plan = latestExercisePlan
        if (plan == null || plan.exerciseStartBlocked || plan.exercises.isEmpty()) {
            workoutProgress = null
            return@LaunchedEffect
        }
        val exerciseIds = plan.exercises.map { ExerciseId.valueOf(it.id) }
        val workout = appContainer.workoutRepository.getOrCreateOpenWorkout(
            plan.profileId,
            plan.planId,
            exerciseIds,
        )
        appContainer.workoutRepository.observeWorkout(workout.workoutSessionId).collect {
            workoutProgress = it
        }
    }
    fun requestPcCleanup(reason: String, onCleaned: suspend () -> Unit) {
        if (!cleanupGate.begin()) return
        if (session.pairingToken.isBlank()) {
            cleanupGate.failed()
            statusMessage = "Cannot clear the PC session without its pairing credential. Reconnect before leaving the PC."
            return
        }
        cleanupRequester.requestSessionCleanup(
            session = session,
            reason = reason,
            callback = object : CleanupCallback {
                override fun onSuccess(body: String, cleanedSession: SteplyWebSessionPayload) {
                    coroutineScope.launch {
                        cleanupGate.succeeded()
                        statusMessage = "PC temporary session data was cleared."
                        onCleaned()
                    }
                }

                override fun onFailure(message: String) {
                    coroutineScope.launch {
                        cleanupGate.failed()
                        statusMessage = "PC cleanup failed. Tap End PC session to retry before leaving a shared computer."
                    }
                }
            },
        )
    }

    suspend fun persistLandmarksAndAck(rawJson: String): LandmarkSaveOutcome {
        return runCatching {
            appContainer.landmarkSeriesRepository.persistFinalized(rawJson)
        }.fold(
            onSuccess = { receipt ->
                streamer?.sendLandmarkSeriesAck(LandmarkSeriesJsonCodec.encodeAck(receipt.envelope, receipt.storedAt))
                statusMessage = "Landmark time series saved locally on this phone."
                LandmarkSaveOutcome.STORED
            },
            onFailure = { error ->
                val notReady = error.message?.contains("unknown assessment") == true ||
                    error.message?.contains("unknown attempt") == true ||
                    error.message?.contains("terminal result") == true
                if (notReady) {
                    LandmarkSaveOutcome.NOT_READY
                } else {
                    statusMessage = "Rejected an invalid landmark series: ${error.message ?: "unknown error"}"
                    LandmarkSaveOutcome.REJECTED
                }
            },
        )
    }

    fun endPcSession() {
        autoStartGate.markStoppedByUser()
        streamerOwner.closeCurrent()
        streamer = null
        streaming = false
        requestPcCleanup("mobile-session-ended") {
            appContainer.assessmentSessionRepository.deactivate(assessmentEnvelope.session.assessmentSessionId)
            onBack()
        }
    }

    fun stopStreaming() {
        autoStartGate.markStoppedByUser()
        streamerOwner.closeCurrent()
        streamer = null
        streaming = false
        statusMessage = "Camera stream stopped."
    }

    BackHandler(onBack = ::endPcSession)

    fun startStreaming() {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        autoStartGate.markStarted()
        resultSaved = false
        finalResultReceived = false
        latestExercisePlan = null
        showExerciseMissions = false
        workoutProgress = null
        previewAckTracker.reset()
        confirmedFrames = 0
        lateinit var newStreamer: RemoteCameraStreamer
        newStreamer = RemoteCameraStreamer(
            session = session,
            onStatus = { message -> statusMessage = message },
            onError = { message ->
                if (streamerOwner.closeIfOwned(newStreamer)) {
                    statusMessage = message
                    streaming = false
                    streamer = null
                }
            },
            assessmentSessionId = assessmentEnvelope.session.assessmentSessionId,
            lastRevision = { currentAssessmentEnvelope.revision },
            sessionSnapshot = {
                AssessmentSessionJsonCodec.sessionToJson(currentAssessmentEnvelope.session).toString()
            },
            careAgentProjection = { currentCareProjection },
            onAssessmentUpdate = { envelopeJson ->
                coroutineScope.launch {
                    runCatching {
                        val decoded = AssessmentSessionJsonCodec.decode(envelopeJson)
                        val updateResult = appContainer.assessmentSessionRepository.applyEnvelope(envelopeJson)
                        decoded to updateResult
                    }.onSuccess {
                        val (decoded, updateResult) = it
                        streamer?.sendAssessmentAck(decoded.messageId, decoded.session.assessmentSessionId, decoded.revision)
                        if (updateResult == AssessmentUpdateResult.APPLIED && decoded.session.hasScoredAggregate()) {
                            appContainer.careAgentEventIngestor.assessmentUpdated(
                                profileId = decoded.session.profileId,
                                assessmentSessionId = decoded.session.assessmentSessionId,
                                revision = decoded.revision,
                                occurredAt = decoded.session.updatedAt,
                            )
                        }
                        statusMessage = "Assessment state updated and saved on this phone."
                        val queued = pendingLandmarkPayloads.toList()
                        queued.forEach { pending ->
                            if (persistLandmarksAndAck(pending) != LandmarkSaveOutcome.NOT_READY) {
                                pendingLandmarkPayloads.remove(pending)
                            }
                        }
                    }.onFailure { error ->
                        statusMessage = "Rejected an invalid assessment update: ${error.message ?: "unknown error"}"
                    }
                }
            },
            onLandmarkSeries = { landmarkJson ->
                coroutineScope.launch {
                    runCatching { LandmarkSeriesJsonCodec.decodeFinalized(landmarkJson) }.onSuccess {
                        if (persistLandmarksAndAck(landmarkJson) == LandmarkSaveOutcome.NOT_READY &&
                            landmarkJson !in pendingLandmarkPayloads
                        ) {
                            pendingLandmarkPayloads += landmarkJson
                        }
                    }.onFailure { error ->
                        statusMessage = "Rejected an invalid landmark series: ${error.message ?: "unknown error"}"
                    }
                }
            },
            onCameraFrameAck = { ack ->
                if (streamerOwner.owns(newStreamer) && previewAckTracker.record(ack)) {
                    confirmedFrames = previewAckTracker.confirmedFrameCount
                }
            },
            onFinalResult = {
                statusMessage = "A legacy single-test result was ignored. Waiting for the aggregate assessment update."
            },
        )
        streamerOwner.replace(newStreamer)
        streamer = newStreamer
        streaming = true
        statusMessage = "Connecting to Steply Web: ${session.webSocketUrl}"
        newStreamer.connect()
    }

    val assessmentIncomplete = !assessmentEnvelope.session.hasScoredAggregate()
    LaunchedEffect(hasCameraPermission, assessmentIncomplete, autoStartGate) {
        if (autoStartGate.consumeIfAllowed(hasCameraPermission, assessmentIncomplete)) startStreaming()
    }

    LaunchedEffect(assessmentEnvelope.revision) {
        val aggregateReady = assessmentEnvelope.session.hasScoredAggregate()
        finalResultReceived = aggregateReady
        resultSaved = aggregateReady
        latestExercisePlan = if (aggregateReady) parseRecommendedExercisePlan(assessmentEnvelope.session) else null
        if (aggregateReady && streaming) {
            streamerOwner.closeCurrent()
            streamer = null
            streaming = false
            savedHistoryCount = 1
            statusMessage = "STEADI assessment complete. Aggregate result saved to local history."
        }
    }

    DisposableEffect(streamerOwner) {
        onDispose {
            streamerOwner.closeCurrent()
        }
    }

    SteplyScaffold(
        title = "Camera stream",
        subtitle = "Stream phone camera frames to your PC for analysis.",
        onBack = ::endPcSession,
    ) { paddingValues ->
        SteplyScreenColumn(paddingValues = paddingValues) {
            StreamingStatusCard(
                streaming = streaming,
                statusMessage = statusMessage,
                confirmedFrames = confirmedFrames,
                resultSaved = resultSaved,
                savedHistoryCount = savedHistoryCount,
            )

            if (hasCameraPermission) {
                CameraPreviewCard(
                    streamer = streamer,
                    streaming = streaming,
                    cameraMessage = cameraMessage,
                    onCameraStatus = { cameraMessage = it },
                    onCameraError = { cameraMessage = it },
                )
            } else {
                CameraPermissionCard(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }

            if (!finalResultReceived || streaming) {
                if (streaming) {
                    SteplyDestructiveButton(
                        text = "Stop streaming",
                        icon = Icons.Default.Close,
                        onClick = ::stopStreaming,
                    )
                } else {
                    SteplyPrimaryButton(
                        text = "Start camera stream",
                        icon = Icons.Default.PlayArrow,
                        onClick = ::startStreaming,
                    )
                }
            }

            val currentExercisePlan = latestExercisePlan
            val checkedMissionIds = workoutProgress?.completedExerciseIds.orEmpty().map { it.name }.toSet()
            if (currentExercisePlan != null) {
                ResultReadyCard(
                    plan = currentExercisePlan,
                    resultSaved = resultSaved,
                    completedCount = checkedMissionIds.size,
                    onViewExercises = { showExerciseMissions = !showExerciseMissions },
                    expanded = showExerciseMissions,
                )

                if (showExerciseMissions) {
                    RecommendedExerciseMissionList(
                        plan = currentExercisePlan,
                        checkedMissionIds = checkedMissionIds,
                        onToggleMission = { missionId ->
                            val workoutId = workoutProgress?.workoutSessionId
                            if (workoutId != null) coroutineScope.launch {
                                appContainer.workoutRepository.setExerciseCompleted(
                                    workoutId,
                                    ExerciseId.valueOf(missionId),
                                    missionId !in checkedMissionIds,
                                )
                            }
                        },
                    )
                }
            } else if (finalResultReceived) {
                ResultSavedCard(
                    resultSaved = resultSaved,
                    onViewHistory = onViewHistory,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SteplySecondaryButton(
                    text = "Change",
                    icon = Icons.Default.Person,
                    onClick = onChangeProfile,
                    modifier = Modifier.weight(1f),
                )
                SteplySecondaryButton(
                    text = "History",
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = onViewHistory,
                    modifier = Modifier.weight(1f),
                )
            }
            SteplyDestructiveButton(
                text = "End PC session",
                icon = Icons.Default.Close,
                onClick = ::endPcSession,
            )
        }
    }
}

private enum class LandmarkSaveOutcome {
    STORED,
    NOT_READY,
    REJECTED,
}

@Composable
private fun ResultSavedCard(
    resultSaved: Boolean,
    onViewHistory: () -> Unit,
) {
    SteplyCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (resultSaved) "Analysis result saved" else "Analysis result received",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (resultSaved) {
                        "The PC result is now available in local history."
                    } else {
                        "Steply received the PC result and is updating local history."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }
        SteplyPrimaryButton(
            text = "View history",
            icon = Icons.AutoMirrored.Filled.List,
            onClick = onViewHistory,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamingStatusCard(
    streaming: Boolean,
    statusMessage: String,
    confirmedFrames: Int,
    resultSaved: Boolean,
    savedHistoryCount: Int,
) {
    val pcReceiving = confirmedFrames > 0
    SteplyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (streaming) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = if (streaming) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        pcReceiving -> "PC receiving camera"
                        streaming -> "Starting camera stream"
                        else -> "PC session linked"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                text = "PC session linked",
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            StatusChip(
                text = when {
                    pcReceiving -> "PC receiving"
                    streaming -> "Awaiting PC confirmation"
                    else -> "Streaming stopped"
                },
                color = if (pcReceiving) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (pcReceiving) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            StatusChip(
                text = "PC confirmed $confirmedFrames",
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusChip(
                text = if (resultSaved) "Result saved" else "No result yet",
                color = if (resultSaved) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (resultSaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (savedHistoryCount > 0) {
                StatusChip(
                    text = "Saved $savedHistoryCount",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewCard(
    streamer: RemoteCameraStreamer?,
    streaming: Boolean,
    cameraMessage: String,
    onCameraStatus: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    SteplyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Camera preview",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusChip(
                text = if (streaming) "Capturing" else "Ready",
                color = if (streaming) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (streaming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        CameraStreamPreview(
            remoteCameraStreamer = streamer,
            onCameraStatus = onCameraStatus,
            onCameraError = onCameraError,
        )
        Text(
            text = cameraMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraPermissionCard(
    onRequestPermission: () -> Unit,
) {
    SteplyCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text = "Camera permission needed",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = "Allow camera access to show the preview and stream frames to the linked PC session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f),
        )
        SteplyPrimaryButton(
            text = "Allow camera access",
            icon = Icons.Default.CameraAlt,
            onClick = onRequestPermission,
        )
    }
}

@Composable
private fun ResultReadyCard(
    plan: RecommendedExercisePlan,
    resultSaved: Boolean,
    completedCount: Int,
    expanded: Boolean,
    onViewExercises: () -> Unit,
) {
    val totalCount = plan.exercises.size
    val recommendationLabel = formatRecommendationLevelLabel(plan.recommendationLevel)

    SteplyCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (resultSaved) "Analysis result saved" else "Analysis result received",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${plan.testLabel} - $recommendationLabel - $completedCount/$totalCount exercises done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
                if (plan.professionalReviewRequired) {
                    Text(
                        text = "Exercise start is blocked until a professional approves this Level A plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        SteplyPrimaryButton(
            text = if (expanded) "Hide recommended exercises" else "View recommended exercises",
            icon = Icons.Default.CheckCircle,
            onClick = onViewExercises,
        )
    }
}
