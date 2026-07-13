package com.steply.app.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.steply.app.domain.model.UserProfile
import com.steply.app.ui.screens.components.EmptyStateCard
import com.steply.app.ui.screens.components.LocalDataNoticeCard
import com.steply.app.ui.screens.components.ProfileAvatar
import com.steply.app.ui.screens.components.SteplySecondaryButton
import com.steply.app.ui.screens.components.StatusChip
import com.steply.app.ui.screens.components.SteplyCard
import com.steply.app.ui.screens.components.SteplyPrimaryButton
import com.steply.app.ui.screens.components.SteplyScaffold
import com.steply.app.ui.screens.components.SteplyScreenColumn

@Composable
fun ProfileListScreen(
    uiState: ProfileListUiState,
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onArchiveProfile: (UserProfile) -> Unit,
    onConfirmArchive: () -> Unit,
    onDismissArchive: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    uiState.archiveTarget?.let {
        AlertDialog(
            onDismissRequest = onDismissArchive,
            title = {
                Text(
                    text = "Delete this profile?",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "This permanently deletes this profile, its assessments, workouts, care history, landmarks, reports, and scheduled actions from this phone. Other profiles are not changed.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmArchive,
                    enabled = !uiState.isWorking,
                ) {
                    Text("Delete profile")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissArchive,
                    enabled = !uiState.isWorking,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    SteplyScaffold(
        title = "Profiles",
        subtitle = "Select the active profile for PC camera checks.",
        onBack = onBack,
    ) { paddingValues ->
        SteplyScreenColumn(paddingValues = paddingValues) {
            LocalDataNoticeCard(
                text = "Each profile keeps its own local movement history on this device.",
            )

            if (uiState.profiles.isEmpty()) {
                EmptyStateCard(
                    title = "Create your first local profile",
                    message = "Add a name or nickname so Steply can keep movement checks and recommendations organized on this phone.",
                    icon = Icons.Default.Person,
                    actionText = "Create profile",
                    onAction = onAddProfile,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    uiState.profiles.forEach { profile ->
                        ProfileListItem(
                            profile = profile,
                            selected = profile.id == uiState.selectedUserId,
                            onSelect = { onSelectProfile(profile.id) },
                            onEdit = { onEditProfile(profile.id) },
                            onArchive = { onArchiveProfile(profile) },
                        )
                    }
                }

                SteplyPrimaryButton(
                    text = "Add profile",
                    icon = Icons.Default.Add,
                    onClick = onAddProfile,
                )
            }

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ProfileListItem(
    profile: UserProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    SteplyCard(
        modifier = Modifier.clickable(onClick = onSelect),
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(displayName = profile.displayName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor,
                )
                Text(
                    text = "Birth year ${profile.birthYear}${profile.heightCm?.let { " - ${it} cm" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                )
                StatusChip(
                    text = if (selected) "Active profile" else "Tap to select",
                    color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected profile",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SteplySecondaryButton(
                text = "Edit",
                icon = Icons.Default.Edit,
                onClick = onEdit,
                modifier = Modifier.weight(1f),
            )
            SteplySecondaryButton(
                text = "Delete",
                icon = Icons.Default.Person,
                onClick = onArchive,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
