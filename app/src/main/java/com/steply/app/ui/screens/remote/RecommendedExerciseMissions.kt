package com.steply.app.ui.screens.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.steply.app.ui.screens.components.EmptyStateCard
import com.steply.app.ui.screens.components.StatusChip
import com.steply.app.ui.screens.components.SteplyCard
import com.steply.app.ui.screens.components.SteplySpacing
import com.steply.app.ui.screens.components.formatRecommendationLevelLabel
import kotlinx.coroutines.delay
import org.json.JSONObject

data class RecommendedExerciseMission(
    val id: String,
    val title: String,
    val description: String,
    val safetyNote: String,
    val durationSeconds: Int,
)

data class RecommendedExercisePlan(
    val testLabel: String,
    val recommendationLevel: String,
    val exercises: List<RecommendedExerciseMission>,
)

@Composable
fun RecommendedExerciseMissionList(
    plan: RecommendedExercisePlan,
    checkedMissionIds: Set<String>,
    onToggleMission: (String) -> Unit,
) {
    if (plan.exercises.isEmpty()) {
        EmptyStateCard(
            title = "No recommended exercises yet",
            message = "Complete an analysis to receive a personalized plan.",
            icon = Icons.Default.CheckCircle,
        )
        return
    }

    val completedCount = plan.exercises.count { it.id in checkedMissionIds }
    val progress = completedCount.toFloat() / plan.exercises.size.toFloat()
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
                    text = "Recommended exercises",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${plan.testLabel} - $recommendationLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
            StatusChip(
                text = "$completedCount/${plan.exercises.size} done",
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface,
        )
    }

    plan.exercises.forEachIndexed { index, mission ->
        MissionCheckCard(
            index = index,
            mission = mission,
            isCompleted = mission.id in checkedMissionIds,
            onDone = { onToggleMission(mission.id) },
        )
    }
}

@Composable
private fun MissionCheckCard(
    index: Int,
    mission: RecommendedExerciseMission,
    isCompleted: Boolean,
    onDone: () -> Unit,
) {
    var isCompleting by remember(mission.id) { mutableStateOf(false) }

    LaunchedEffect(isCompleting, isCompleted) {
        if (isCompleting && !isCompleted) {
            delay(650)
            onDone()
        }
    }

    AnimatedVisibility(
        visible = !isCompleted,
        exit = fadeOut() + shrinkVertically(),
    ) {
        SteplyCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = isCompleting || isCompleted,
                    onCheckedChange = { checked ->
                        if (checked && !isCompleting && !isCompleted) {
                            isCompleting = true
                        }
                    },
                    enabled = !isCompleting && !isCompleted,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${index + 1}. ${mission.title}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isCompleting) {
                            StatusChip(
                                text = "Done",
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = mission.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Recommended time: ${(mission.durationSeconds / 60).coerceAtLeast(1)} min",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SafetyNoteSurface(text = mission.safetyNote)
                }
            }
        }
    }
}

@Composable
private fun SafetyNoteSurface(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SteplySpacing.NoticePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

fun parseRecommendedExercisePlan(resultJson: String): RecommendedExercisePlan? {
    // Display only the recommendation plan produced by the PC web analysis.
    // Mobile must not derive exercises from pose landmarks or raw movement data.
    return runCatching {
        val json = JSONObject(resultJson)
        val recommendations = json.optJSONArray("recommendations") ?: return null
        val exercises = mutableListOf<RecommendedExerciseMission>()
        for (index in 0 until recommendations.length()) {
            val item = recommendations.optJSONObject(index) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
            exercises += RecommendedExerciseMission(
                id = "${json.optString("id", "result")}-$index-$title",
                title = title,
                description = item.optString("description").takeIf { it.isNotBlank() } ?: "Practice this movement gently.",
                safetyNote = item.optString("safetyNote").takeIf { it.isNotBlank() } ?: "Stop if there is pain, dizziness, or discomfort.",
                durationSeconds = item.optInt("durationSeconds", 60),
            )
        }
        if (exercises.isEmpty()) return null
        val rawTestLabel = json.optString("testLabel").takeIf { it.isNotBlank() }
            ?: json.optString("testType").takeIf { it.isNotBlank() }
            ?: "Movement Check"
        RecommendedExercisePlan(
            testLabel = rawTestLabel.toDisplayLabel(),
            recommendationLevel = json.optString("recommendationLevel").takeIf { it.isNotBlank() }
                ?: "Recommended",
            exercises = exercises,
        )
    }.getOrNull()
}

private fun String.toDisplayLabel(): String {
    val trimmed = trim()
    if ('_' !in trimmed && trimmed.any { it.isLowerCase() }) return trimmed

    return trimmed
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { char -> char.titlecase() }
}
