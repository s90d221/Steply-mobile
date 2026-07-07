package com.steply.app.ui.screens.remote

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.steply.app.AppContainer
import com.steply.app.remote.RemoteCameraStreamer
import com.steply.app.sync.CleanupCallback
import com.steply.app.sync.PcSessionCleanupRequester
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
    sessionId: String,
    serverUrl: String,
    pairingToken: String,
    expiresAtEpochMs: Long,
    tlsCertSha256: String?,
    onBack: () -> Unit,
    onChangeProfile: () -> Unit,
    onViewHistory: () -> Unit,
) {
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

    val session = remember(sessionId, serverUrl, pairingToken, expiresAtEpochMs, tlsCertSha256) {
        SteplyWebSessionPayload(
            sessionId = sessionId,
            serverUrl = serverUrl,
            expiresAtEpochMs = expiresAtEpochMs,
            pairingToken = pairingToken,
            tlsCertSha256 = tlsCertSha256,
        )
    }
    var streamer by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf<RemoteCameraStreamer?>(null) }
    var streaming by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf(false) }
    var statusMessage by remember(session.webSocketUrl, session.tlsCertSha256) {
        mutableStateOf("Ready to stream camera frames to the PC.")
    }
    var cameraMessage by remember { mutableStateOf("Checking camera permission.") }
    var sentFrames by remember { mutableIntStateOf(0) }
    var savedHistoryCount by remember { mutableIntStateOf(0) }
    var latestExercisePlan by remember { mutableStateOf<RecommendedExercisePlan?>(null) }
    var finalResultReceived by remember { mutableStateOf(false) }
    var resultSaved by remember { mutableStateOf(false) }
    var showExerciseMissions by remember { mutableStateOf(false) }
    var checkedMissionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedVideoUri by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf<Uri?>(null) }
    var streamSource by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf(StreamSource.Camera) }
    var startPickedVideoStream by remember(session.webSocketUrl, session.tlsCertSha256) { mutableStateOf(false) }
    var cleanupRequested by remember(session.sessionId, session.serverUrl) { mutableStateOf(false) }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            startPickedVideoStream = true
        }
    }

    fun requestPcCleanup(reason: String) {
        if (cleanupRequested || session.pairingToken.isBlank()) return
        cleanupRequested = true
        cleanupRequester.requestSessionCleanup(
            session = session,
            reason = reason,
            callback = object : CleanupCallback {
                override fun onSuccess(body: String, cleanedSession: SteplyWebSessionPayload) {
                    coroutineScope.launch {
                        statusMessage = "PC temporary session data was cleared."
                    }
                }

                override fun onFailure(message: String) {
                    coroutineScope.launch {
                        statusMessage = "PC cleanup request failed. Close the PC session before leaving a shared computer."
                    }
                }
            },
        )
    }

    fun stopStreaming() {
        requestPcCleanup("mobile-stream-stopped")
        streamer?.close()
        streamer = null
        streaming = false
        statusMessage = if (streamSource == StreamSource.DemoVideo) {
            "Demo video stream stopped."
        } else {
            "Camera stream stopped."
        }
    }

    fun startStreaming(source: StreamSource) {
        if (source == StreamSource.Camera && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (source == StreamSource.DemoVideo && selectedVideoUri == null) {
            videoPickerLauncher.launch("video/*")
            return
        }
        streamer?.close()
        streamSource = source
        resultSaved = false
        finalResultReceived = false
        latestExercisePlan = null
        showExerciseMissions = false
        checkedMissionIds = emptySet()
        val newStreamer = RemoteCameraStreamer(
            session = session,
            onStatus = { message -> statusMessage = message },
            onError = { message ->
                statusMessage = message
                streaming = false
                streamer = null
            },
            onFinalResult = { resultJson ->
                finalResultReceived = true
                parseRecommendedExercisePlan(resultJson)?.let { plan ->
                    latestExercisePlan = plan
                    showExerciseMissions = false
                    checkedMissionIds = emptySet()
                }
                coroutineScope.launch {
                    runCatching {
                        appContainer.movementHistoryRepository.saveFromPcResult(resultJson)
                    }.onSuccess {
                        savedHistoryCount += 1
                        resultSaved = true
                        statusMessage = "PC analysis complete. Result saved to local history."
                    }.onFailure { error ->
                        resultSaved = false
                        statusMessage = "Could not save the PC result: ${error.message ?: "unknown error"}"
                    }
                }
            },
        )
        streamer = newStreamer
        streaming = true
        sentFrames = 0
        statusMessage = if (source == StreamSource.DemoVideo) {
            "Connecting demo video stream to Steply Web: ${session.webSocketUrl}"
        } else {
            "Connecting to Steply Web: ${session.webSocketUrl}"
        }
        newStreamer.connect()
    }

    LaunchedEffect(startPickedVideoStream, selectedVideoUri) {
        if (startPickedVideoStream && selectedVideoUri != null) {
            startPickedVideoStream = false
            startStreaming(StreamSource.DemoVideo)
        }
    }

    DisposableEffect(session.webSocketUrl) {
        onDispose {
            requestPcCleanup("mobile-screen-disposed")
            streamer?.close()
        }
    }

    SteplyScaffold(
        title = "Camera stream",
        subtitle = "Stream phone camera frames to your PC for analysis.",
        onBack = onBack,
    ) { paddingValues ->
        SteplyScreenColumn(paddingValues = paddingValues) {
            StreamingStatusCard(
                streaming = streaming,
                statusMessage = statusMessage,
                sentFrames = sentFrames,
                resultSaved = resultSaved,
                savedHistoryCount = savedHistoryCount,
            )

            if (streamSource == StreamSource.DemoVideo && selectedVideoUri != null) {
                DemoVideoPreviewCard(
                    videoUri = selectedVideoUri,
                    streamer = streamer,
                    streaming = streaming,
                    videoMessage = cameraMessage,
                    onVideoStatus = { cameraMessage = it },
                    onVideoError = { cameraMessage = it },
                    onFrameSent = {
                        if (streaming && !resultSaved) sentFrames += 1
                    },
                )
            } else if (hasCameraPermission) {
                CameraPreviewCard(
                    streamer = streamer,
                    streaming = streaming,
                    cameraMessage = cameraMessage,
                    onCameraStatus = { cameraMessage = it },
                    onCameraError = { cameraMessage = it },
                    onFrameSent = {
                        if (streaming && !resultSaved) sentFrames += 1
                    },
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
                        onClick = { startStreaming(StreamSource.Camera) },
                    )
                    SteplySecondaryButton(
                        text = "Start demo video",
                        icon = Icons.Default.VideoLibrary,
                        onClick = { videoPickerLauncher.launch("video/*") },
                    )
                }
            }

            val currentExercisePlan = latestExercisePlan
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
                            checkedMissionIds = if (missionId in checkedMissionIds) {
                                checkedMissionIds - missionId
                            } else {
                                checkedMissionIds + missionId
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
        }
    }
}

private enum class StreamSource {
    Camera,
    DemoVideo,
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
    sentFrames: Int,
    resultSaved: Boolean,
    savedHistoryCount: Int,
) {
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
                    text = if (streaming) "Streaming to PC" else "PC session linked",
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
                text = "PC connected",
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            StatusChip(
                text = if (streaming) "Streaming active" else "Streaming stopped",
                color = if (streaming) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (streaming) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            StatusChip(
                text = "Frames $sentFrames",
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
    onFrameSent: () -> Unit,
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
                text = if (streaming) "Live" else "Ready",
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
            onFrameSent = onFrameSent,
        )
        Text(
            text = cameraMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DemoVideoPreviewCard(
    videoUri: Uri?,
    streamer: RemoteCameraStreamer?,
    streaming: Boolean,
    videoMessage: String,
    onVideoStatus: (String) -> Unit,
    onVideoError: (String) -> Unit,
    onFrameSent: () -> Unit,
) {
    SteplyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Demo video preview",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusChip(
                text = if (streaming) "Demo" else "Ready",
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
        GalleryVideoStreamPreview(
            videoUri = videoUri,
            remoteCameraStreamer = streamer,
            onVideoStatus = onVideoStatus,
            onVideoError = onVideoError,
            onFrameSent = onFrameSent,
        )
        Text(
            text = videoMessage,
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
            }
        }
        SteplyPrimaryButton(
            text = if (expanded) "Hide recommended exercises" else "View recommended exercises",
            icon = Icons.Default.CheckCircle,
            onClick = onViewExercises,
        )
    }
}
