package com.steply.app.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.steply.app.AppContainer
import com.steply.app.domain.model.AssessmentSummary
import com.steply.app.domain.model.AssessmentSex
import com.steply.app.domain.steadi.SteadiScorer
import com.steply.app.ui.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.steply.app.report.WeeklyReportFormatter
import com.steply.app.report.WeeklyReportGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class HistoryUiState(
    val selectedUserId: String? = null,
    val summaries: List<AssessmentSummary> = emptyList(),
    val chairStandCutoff: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val appContainer: AppContainer,
) : ViewModel() {
    private val summaries = appContainer.settingsRepository.selectedUserId.flatMapLatest { profileId ->
        profileId?.let(appContainer.assessmentSummaryRepository::observeValidByProfile) ?: flowOf(emptyList())
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        appContainer.settingsRepository.selectedUserId,
        summaries,
        appContainer.userProfileRepository.observeActiveProfiles(),
    ) { selectedUserId, summaries, profiles ->
        val profile = profiles.firstOrNull { it.id == selectedUserId }
        val sex = when (profile?.gender?.trim()?.uppercase()) {
            "MALE", "M", "MAN" -> AssessmentSex.MALE
            "FEMALE", "F", "WOMAN" -> AssessmentSex.FEMALE
            else -> null
        }
        HistoryUiState(
            selectedUserId = selectedUserId,
            summaries = summaries,
            chairStandCutoff = if (profile != null && sex != null) SteadiScorer.chairStandCutoff(profile.age, sex) else null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HistoryUiState(),
    )

    fun prepareWeeklyReport(onReady: (String) -> Unit, onFailure: () -> Unit = {}) {
        viewModelScope.launch {
            val profileId = appContainer.settingsRepository.selectedUserId.first()
            if (profileId == null) {
                onFailure()
                return@launch
            }
            runCatching {
                WeeklyReportFormatter.format(
                    WeeklyReportGenerator.generate(appContainer.steplyDataContractRepository.buildWeeklyReport(profileId)),
                )
            }.onSuccess(onReady).onFailure { onFailure() }
        }
    }

    companion object {
        fun factory(appContainer: AppContainer) = viewModelFactory {
            HistoryViewModel(appContainer)
        }
    }
}
