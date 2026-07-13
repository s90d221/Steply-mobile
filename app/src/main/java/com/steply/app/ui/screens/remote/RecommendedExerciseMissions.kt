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
import com.steply.app.domain.model.AssessmentSession
import com.steply.app.domain.model.ExerciseCategory
import com.steply.app.domain.model.OtagoPlanStatus
import com.steply.app.domain.model.PrescribedExercise
import com.steply.app.domain.model.PrescriptionStatus
import com.steply.app.domain.model.hasScoredAggregate

data class RecommendedExerciseMission(
    val id: String,
    val title: String,
    val description: String,
    val safetyNote: String,
    val durationSeconds: Int,
)

data class RecommendedExercisePlan(
    val profileId: String,
    val planId: String,
    val testLabel: String,
    val recommendationLevel: String,
    val exercises: List<RecommendedExerciseMission>,
    val vulnerabilityIds: List<String> = emptyList(),
    val professionalReviewRequired: Boolean = false,
    val supervisionRequirement: String? = null,
    val exerciseStartBlocked: Boolean = false,
)

internal data class BlockedExercisePlanCopy(
    val title: String,
    val message: String,
)

internal fun blockedExercisePlanCopy(plan: RecommendedExercisePlan): BlockedExercisePlanCopy =
    if (plan.professionalReviewRequired) {
        BlockedExercisePlanCopy(
            title = "Professional approval required",
            message = "This supported plan is saved, but exercise cannot start until a professional approves it.",
        )
    } else {
        BlockedExercisePlanCopy(
            title = "No targeted exercises available",
            message = "No V1-V9 exercise target was identified. Review the assessment results before starting an exercise plan.",
        )
    }

@Composable
fun RecommendedExerciseMissionList(
    plan: RecommendedExercisePlan,
    checkedMissionIds: Set<String>,
    onToggleMission: (String) -> Unit,
) {
    if (plan.exerciseStartBlocked) {
        val copy = blockedExercisePlanCopy(plan)
        EmptyStateCard(
            title = copy.title,
            message = copy.message,
            icon = Icons.Default.Warning,
        )
        return
    }
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
    AnimatedVisibility(visible = true) {
        SteplyCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = isCompleted,
                    onCheckedChange = { onDone() },
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
                        if (isCompleted) {
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

fun parseRecommendedExercisePlan(session: AssessmentSession): RecommendedExercisePlan? {
    if (!session.hasScoredAggregate()) return null
    val plan = session.exercisePrescription.plan ?: return null
    val blocked = session.exercisePrescription.status == PrescriptionStatus.PENDING_PROFESSIONAL_REVIEW ||
        plan.status != OtagoPlanStatus.ACTIVE
    val safetyNote = buildList {
        addAll(plan.safetyNotices)
        add("Supervision: ${plan.supervisionRequirement.name}")
    }.joinToString(" ")
    val exercises = buildList {
        (plan.warmups + plan.selectedExercises).forEach { add(it.toMission(safetyNote)) }
        plan.walkingPlan?.let { walking ->
            add(
                RecommendedExerciseMission(
                    id = walking.exerciseId.name,
                    title = "Walking plan",
                    description = "${walking.targetMinutes} minutes at usual pace, ${walking.weeklyFrequency} times weekly; may split into ${walking.splitMinutes.joinToString("+")} minutes.",
                    safetyNote = safetyNote,
                    durationSeconds = walking.targetMinutes * 60,
                ),
            )
        }
    }
    return RecommendedExercisePlan(
        profileId = session.profileId,
        planId = plan.planId,
        testLabel = "STEADI Assessment",
        recommendationLevel = session.steadi.risk.name,
        exercises = exercises,
        vulnerabilityIds = plan.vulnerabilityIds.map { it.name },
        professionalReviewRequired = plan.requiresProfessionalReview,
        supervisionRequirement = plan.supervisionRequirement.name,
        exerciseStartBlocked = blocked,
    )
}

private fun PrescribedExercise.toMission(safetyNote: String): RecommendedExerciseMission {
    val dosage = buildList {
        repetitions?.let { add("$it repetitions") }
        repetitionsPerSide?.let { add("$it repetitions per side") }
        steps?.let { add("$it steps") }
        holdSeconds?.let { add("hold $it seconds") }
        sets?.let { add("$it sets") }
        if (weakSideExtraSets > 0) add("$weakSideExtraSets extra set on the weaker side")
    }.joinToString(", ")
    val load = when (weightMode.name) {
        "NONE" -> "no added weight"
        else -> "$weightMode ${weightMinKg}-${weightMaxKg} kg"
    }
    val detail = buildList {
        add("Level ${level.name}; $dosage; support: ${supportRequirement.name}; $load.")
        if (category == ExerciseCategory.STRENGTH) {
            add("Tempo ${tempoUpMinSeconds}-${tempoUpMaxSeconds}s up, ${tempoDownMinSeconds}-${tempoDownMaxSeconds}s down; rest ${restMinSeconds}-${restMaxSeconds}s. $breathingRule")
        } else {
            add(breathingRule)
        }
        add("Camera verification: ${cameraVerification.name}.")
    }.joinToString(" ")
    val seconds = when {
        holdSeconds != null -> holdSeconds * (sets ?: 1)
        repetitionsPerSide != null -> repetitionsPerSide * 2 * (sets ?: 1) * 5
        repetitions != null -> repetitions * (sets ?: 1) * 5
        steps != null -> steps * (sets ?: 1) * 2
        else -> 1
    }
    return RecommendedExerciseMission(exerciseId.name, displayName, detail, safetyNote, seconds.coerceAtLeast(1))
}
