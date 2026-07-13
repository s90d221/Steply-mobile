package com.steply.app.ui.screens.history

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.steply.app.domain.model.AssessmentSummary
import com.steply.app.domain.model.BalanceStage
import com.steply.app.domain.steadi.SteadiScorer
import com.steply.app.ui.screens.components.EmptyStateCard
import com.steply.app.ui.screens.components.SteplyCard
import com.steply.app.ui.screens.components.SteplyScaffold
import com.steply.app.ui.screens.components.SteplyScreenColumn
import com.steply.app.ui.screens.components.SteplySecondaryButton
import java.util.Locale

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onPrepareWeeklyReport: ((String) -> Unit, () -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingReportText by remember { mutableStateOf<String?>(null) }
    var reportShareMessage by remember { mutableStateOf<String?>(null) }

    pendingReportText?.let { reportText ->
        WeeklyReportConsentDialog(
            reportText = reportText,
            onDismiss = { pendingReportText = null },
            onShare = {
                pendingReportText = null
                reportShareMessage = if (shareWeeklyReport(context, reportText)) {
                    "Share sheet opened. Choose a family or care-team contact to send the report."
                } else {
                    "No sharing app is available on this device."
                }
            },
        )
    }

    SteplyScaffold(
        title = "History",
        subtitle = "PC analysis results saved on this phone.",
        onBack = onBack,
    ) { paddingValues ->
        SteplyScreenColumn(paddingValues = paddingValues) {
            if (uiState.summaries.isEmpty()) {
                EmptyStateCard(
                    title = "No history saved yet",
                    message = "Complete a PC analysis to see saved movement results and recommendations here.",
                    icon = Icons.Default.Refresh,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    WeeklyReportShareCard(
                        selectedProfileId = uiState.selectedUserId,
                        statusMessage = reportShareMessage,
                        onPrepareReport = {
                            onPrepareWeeklyReport(
                                { reportText ->
                                    pendingReportText = reportText
                                },
                                { reportShareMessage = "A strict weekly snapshot could not be prepared." },
                            )
                        },
                    )
                    CanonicalHistoryOverviewTrends(uiState.summaries, uiState.chairStandCutoff)
                }
            }
        }
    }
}

@Composable
private fun CanonicalHistoryOverviewTrends(
    summaries: List<AssessmentSummary>,
    chairStandCutoff: Int?,
) {
    if (summaries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Trend overview",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CanonicalTrendCard(
            title = "30 sec Chair Stand",
            subtitle = "Valid complete stands · last 5",
            values = summaries.sortedBy { it.completedAt }.takeLast(5).map { it.chairStandRepetitions.toDouble() },
            baseline = chairStandCutoff?.toDouble(),
            baselineLabel = chairStandCutoff?.let { "CDC age/sex reference: $it" },
        )
        canonicalBalanceSeries(summaries).forEach { series ->
            val tandem = series.stage == BalanceStage.TANDEM
            CanonicalTrendCard(
                title = "4-Stage Balance · ${series.stage.name.replace('_', ' ')}",
                subtitle = "Hold seconds · last 5",
                values = series.values,
                baseline = SteadiScorer.TANDEM_CUTOFF_SECONDS,
                baselineLabel = "10-second posture reference",
                emphasized = tandem,
            )
        }
    }
}

@Composable
private fun CanonicalTrendCard(
    title: String,
    subtitle: String,
    values: List<Double>,
    baseline: Double?,
    baselineLabel: String?,
    emphasized: Boolean = false,
) {
    if (values.isEmpty()) return
    val delta = values.last() - values.first()
    val color = when {
        delta > 0 -> Color(0xFF13A88E)
        delta < 0 -> Color(0xFFBA1A1A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    SteplyCard(
        containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CanonicalSparklineChart(values, color, baseline)
        baselineLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            text = "${values.joinToString(" → ") { String.format(Locale.US, "%.1f", it) }} · change ${if (delta > 0) "+" else ""}${String.format(Locale.US, "%.1f", delta)}",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun CanonicalSparklineChart(values: List<Double>, color: Color, baseline: Double?) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val scaleValues = if (baseline == null) values else values + baseline
        val min = scaleValues.minOrNull() ?: 0.0
        val max = scaleValues.maxOrNull() ?: 1.0
        val range = (max - min).coerceAtLeast(0.1)
        fun y(value: Double) = size.height - (((value - min) / range).toFloat() * size.height)
        val step = if (values.size > 1) size.width / (values.size - 1) else 0f
        fun x(index: Int) = if (values.size == 1) size.width / 2f else step * index
        baseline?.let {
            drawLine(
                color = outlineColor,
                start = Offset(0f, y(it)),
                end = Offset(size.width, y(it)),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
        values.zipWithNext().forEachIndexed { index, pair ->
            drawLine(color, Offset(x(index), y(pair.first)), Offset(x(index + 1), y(pair.second)), 8f, StrokeCap.Round)
        }
        values.forEachIndexed { index, value -> drawCircle(color, 8f, Offset(x(index), y(value))) }
    }
}

@Composable
private fun WeeklyReportShareCard(
    selectedProfileId: String?,
    statusMessage: String?,
    onPrepareReport: () -> Unit,
) {
    SteplyCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Weekly care report",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (selectedProfileId == null) {
                    "Select a profile before sharing a weekly report."
                } else {
                    "Share saved PC final results for the selected profile with family or a care worker. The report excludes camera frames, pose landmarks, raw JSON, profile id, and session id."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SteplySecondaryButton(
                text = "Review and share weekly report",
                icon = Icons.Default.Share,
                onClick = onPrepareReport,
                enabled = selectedProfileId != null,
            )
            statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WeeklyReportConsentDialog(
    reportText: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share weekly report?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This shares saved PC final results with the contact you choose. It does not include raw camera frames, pose landmarks, raw JSON, profile id, or session id.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = reportText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun shareWeeklyReport(
    context: Context,
    reportText: String,
): Boolean {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Steply weekly movement report")
        putExtra(Intent.EXTRA_TEXT, reportText)
    }
    val chooser = Intent.createChooser(sendIntent, "Share Steply weekly report")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
