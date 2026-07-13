package com.steply.app.ui.screens.history

import com.steply.app.domain.model.AssessmentSummary
import com.steply.app.domain.model.BalanceStage

data class CanonicalBalanceSeries(
    val stage: BalanceStage,
    val values: List<Double>,
)

fun canonicalBalanceSeries(summaries: List<AssessmentSummary>): List<CanonicalBalanceSeries> {
    val recent = summaries.filter { it.valid }.sortedBy { it.completedAt }.takeLast(5)
    return BalanceStage.entries.map { stage ->
        CanonicalBalanceSeries(stage, recent.map { it.balanceSecondsByStage.getValue(stage) })
    }
}
