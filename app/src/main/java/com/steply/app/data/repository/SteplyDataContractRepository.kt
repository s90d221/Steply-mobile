package com.steply.app.data.repository

import com.steply.app.sync.SteplyDataContract
import com.steply.app.sync.SteplyDataContractBuilder
import com.steply.app.sync.SteplyLocalReportData

class SteplyDataContractRepository(
    private val profiles: UserProfileRepository,
    private val assessments: AssessmentSessionRepository,
    private val workouts: WorkoutRepository,
    private val care: CareAgentRepository,
) {
    suspend fun build(profileId: String, generatedAt: Long = System.currentTimeMillis()): SteplyDataContract {
        val profile = requireNotNull(profiles.getProfileById(profileId))
        return SteplyDataContractBuilder.build(
            profile = profile,
            sessions = assessments.getByProfileId(profileId).map { it.envelope.session },
            generatedAt = generatedAt,
        )
    }

    suspend fun buildWeeklyReport(profileId: String, generatedAt: Long = System.currentTimeMillis()): SteplyLocalReportData {
        val profile = requireNotNull(profiles.getProfileById(profileId))
        return SteplyDataContractBuilder.buildLocalReport(
            profile = profile,
            sessions = assessments.getByProfileId(profileId).map { it.envelope.session },
            workouts = workouts.getByProfile(profileId),
            careState = care.getState(profileId),
            decisions = care.getDecisionLogs(profileId),
            generatedAt = generatedAt,
        )
    }
}
