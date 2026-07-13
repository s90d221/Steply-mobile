package com.steply.app.ui.screens.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.steply.app.domain.model.UserProfile
import com.steply.app.ui.screens.components.ProfileAvatar
import com.steply.app.ui.screens.components.RemoteCameraQrScanner
import com.steply.app.ui.screens.components.StatusChip
import com.steply.app.ui.screens.components.SteplyCorners
import com.steply.app.ui.screens.components.SteplyCard
import com.steply.app.ui.screens.components.SteplySpacing
import com.steply.app.ui.screens.components.SteplyPrimaryButton
import com.steply.app.ui.screens.components.SteplyScaffold
import com.steply.app.ui.screens.components.SteplyScreenColumn
import com.steply.app.ui.screens.components.SteplySecondaryButton
import com.steply.app.ui.screens.components.formatRecommendationLevelLabel

@Composable
fun RemoteConnectScreen(
    uiState: RemoteConnectUiState,
    onQrScanned: (String) -> Unit,
    onManualQrChanged: (String) -> Unit,
    onConnectManual: () -> Unit,
    onChangeProfile: () -> Unit,
    onAddProfile: () -> Unit,
    onViewHistory: () -> Unit,
    onToggleMission: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var isQrScannerOpen by remember { mutableStateOf(false) }
    var showExerciseMissions by remember { mutableStateOf(false) }
    val checkedMissionIds = uiState.workoutProgress?.completedExerciseIds.orEmpty().map { it.name }.toSet()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        isQrScannerOpen = granted
    }

    SteplyScaffold(
        title = "Steply",
        subtitle = "Connect to your PC",
    ) { paddingValues ->
        SteplyScreenColumn(paddingValues = paddingValues) {
            SelectedProfileCard(
                profile = uiState.selectedProfile,
                onChangeProfile = onChangeProfile,
                onAddProfile = onAddProfile,
                onViewHistory = onViewHistory,
            )

            QrScanCard(
                profileReady = uiState.selectedProfile != null,
                hasCameraPermission = hasCameraPermission,
                isQrScannerOpen = isQrScannerOpen,
                isConnecting = uiState.isConnecting,
                onOpenScanner = { isQrScannerOpen = true },
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCloseScanner = { isQrScannerOpen = false },
                onQrScanned = { value ->
                    isQrScannerOpen = false
                    onQrScanned(value)
                },
            )

            ConnectionFeedbackCard(uiState = uiState)

            uiState.latestExercisePlan?.let { plan ->
                LatestExercisePlanCard(
                    plan = plan,
                    completedCount = checkedMissionIds.size,
                    expanded = showExerciseMissions,
                    onToggleExpanded = { showExerciseMissions = !showExerciseMissions },
                )

                if (showExerciseMissions) {
                    RecommendedExerciseMissionList(
                        plan = plan,
                        checkedMissionIds = checkedMissionIds,
                        onToggleMission = onToggleMission,
                    )
                }
            }

            ManualPayloadCard(
                manualQrValue = uiState.manualQrValue,
                isConnecting = uiState.isConnecting,
                onManualQrChanged = onManualQrChanged,
                onConnectManual = onConnectManual,
            )
        }
    }
}

@Composable
private fun ConnectionFeedbackCard(
    uiState: RemoteConnectUiState,
) {
    if (!uiState.isConnecting && uiState.errorMessage == null) return

    val isError = uiState.errorMessage != null
    SteplyCard(
        containerColor = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Refresh,
                contentDescription = null,
                tint = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isError) "Check the PC connection" else "Connecting to the PC",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
                Text(
                    text = uiState.errorMessage ?: uiState.statusMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }
    }
}

@Composable
private fun QrScanCard(
    profileReady: Boolean,
    hasCameraPermission: Boolean,
    isQrScannerOpen: Boolean,
    isConnecting: Boolean,
    onOpenScanner: () -> Unit,
    onRequestPermission: () -> Unit,
    onCloseScanner: () -> Unit,
    onQrScanned: (String) -> Unit,
) {
    SteplyCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Connect with a QR code",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Scan the code shown on your PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }

        SeniorStepRow(number = "1", text = "Open Steply Web on your PC.")
        SeniorStepRow(
            number = "2",
            text = if (hasCameraPermission) {
                "Tap Start QR scan, then point at the code."
            } else {
                "Tap Allow, then scan the code."
            },
        )

        if (isQrScannerOpen && hasCameraPermission) {
            RemoteCameraQrScanner(onQrCodeScanned = onQrScanned)
            SteplySecondaryButton(
                text = "Close scanner",
                icon = Icons.Default.QrCodeScanner,
                onClick = onCloseScanner,
            )
        } else {
            if (!hasCameraPermission) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Used only for QR scanning.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            SteplyPrimaryButton(
                text = when {
                    !profileReady -> "Select a profile first"
                    hasCameraPermission -> "Start QR scan"
                    else -> "Allow camera access"
                },
                icon = Icons.Default.QrCodeScanner,
                onClick = if (hasCameraPermission) onOpenScanner else onRequestPermission,
                enabled = profileReady && !isConnecting,
            )
        }
    }
}

@Composable
private fun SeniorStepRow(number: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun LatestExercisePlanCard(
    plan: RecommendedExercisePlan,
    completedCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val totalCount = plan.exercises.size
    val recommendationLabel = formatRecommendationLevelLabel(plan.recommendationLevel)

    SteplyCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Recommended exercises",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "${plan.testLabel} - $recommendationLabel - $completedCount/$totalCount done",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
        }
        SteplySecondaryButton(
            text = if (expanded) "Hide exercises" else "View exercises",
            icon = Icons.Default.CheckCircle,
            onClick = onToggleExpanded,
        )
    }
}

@Composable
private fun ManualPayloadCard(
    manualQrValue: String,
    isConnecting: Boolean,
    onManualQrChanged: (String) -> Unit,
    onConnectManual: () -> Unit,
) {
    SteplyCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Can't scan? Enter QR text",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Paste text from Steply Web.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = manualQrValue,
            onValueChange = onManualQrChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("QR text") },
            placeholder = { Text("Paste QR text here") },
            singleLine = true,
            shape = RoundedCornerShape(SteplyCorners.Field),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            trailingIcon = {
                IconButton(
                    onClick = onConnectManual,
                    enabled = !isConnecting,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connect with QR text",
                    )
                }
            },
            enabled = !isConnecting,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedProfileCard(
    profile: UserProfile?,
    onChangeProfile: () -> Unit,
    onAddProfile: () -> Unit,
    onViewHistory: () -> Unit,
) {
    SteplyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(displayName = profile?.displayName ?: "?")
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = profile?.displayName ?: "No Profile Selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    profile?.let {
                        StatusChip(
                            text = "Active",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = if (profile == null) {
                        "Choose a profile before scanning."
                    } else {
                        "This check will be saved here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile?.let {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SteplySpacing.SmallGap),
                        verticalArrangement = Arrangement.spacedBy(SteplySpacing.SmallGap),
                    ) {
                        StatusChip(
                            text = "Birth year ${it.birthYear}",
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        it.heightCm?.let { heightCm ->
                            StatusChip(
                                text = "$heightCm cm",
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (profile == null) {
            SteplyPrimaryButton(
                text = "Create profile",
                icon = Icons.Default.Person,
                onClick = onAddProfile,
            )
        } else {
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
