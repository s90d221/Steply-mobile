package com.steply.app.care

import com.steply.app.data.repository.AssessmentSessionRepository
import com.steply.app.data.repository.CareAgentRepository
import com.steply.app.data.repository.PersistedAssessmentSession
import com.steply.app.data.repository.UserProfileRepository
import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.AssessmentSession
import com.steply.app.domain.model.ProgressionStatus
import com.steply.app.domain.model.UserProfile
import com.steply.app.domain.model.hasScoredAggregate

fun interface CarePerceptionSource {
    suspend fun perceive(event: CareEvent, perceivedAt: Long): CareInputState
}

class CarePerceptionNotReadyException(message: String) : IllegalStateException(message)

class RoomCarePerceptionSource(
    private val userProfiles: UserProfileRepository,
    private val assessments: AssessmentSessionRepository,
    private val careStates: CareAgentRepository,
    private val config: CareAgentConfig = CareAgentConfigV1.value,
) : CarePerceptionSource {
    override suspend fun perceive(event: CareEvent, perceivedAt: Long): CareInputState {
        val profile = requireNotNull(userProfiles.getProfileById(event.profileId)) {
            "Care agent profile does not exist"
        }
        val sessions = assessments.getByProfileId(event.profileId).map { it.envelope.session }
        val prior = careStates.getState(event.profileId)?.input
        return CareInputStateBuilder(config).build(profile, sessions, prior, event, perceivedAt)
    }
}

class CareInputStateBuilder(
    private val config: CareAgentConfig = CareAgentConfigV1.value,
) {
    fun build(
        profile: UserProfile,
        sessions: List<AssessmentSession>,
        prior: CareInputState?,
        event: CareEvent,
        perceivedAt: Long,
    ): CareInputState {
        require(event.profileId == profile.id)
        validateEventPayload(event)
        val scored = sessions.filter { it.profileId == profile.id && it.hasScoredAggregate() }
            .sortedBy { it.completedAt ?: it.updatedAt }
        val latest = scored.lastOrNull()
            ?: throw CarePerceptionNotReadyException("Care agent requires a completed canonical assessment")
        val clinical = latest.toClinicalReference()
        val validSummaries = scored.mapNotNull { it.toValidSummary() }
            .takeLast(config.recentAssessmentLimit)
        val allTerminalAttempts = sessions.flatMap { session ->
            val tests = session.functionalTests
            tests.fourStageBalance.attempts + tests.chairStand30s.attempts
        }.filter { it.status !in setOf(AssessmentAttemptStatus.IN_PROGRESS, AssessmentAttemptStatus.PAUSED) }
        val invalidNumerator = allTerminalAttempts.count { attempt ->
            attempt.status == AssessmentAttemptStatus.INVALID || attempt.result?.quality?.excludeFromTrends == true
        }
        val invalidDenominator = allTerminalAttempts.size
        val invalidRatio = if (invalidDenominator == 0) 0.0 else invalidNumerator.toDouble() / invalidDenominator
        val exerciseResults = scored.flatMap { it.exercisePrescription.sessionResults }.distinctBy { it.resultId }
        val completedByWeek = (0 until config.lowAdherenceConsecutiveWeeks).map { weekIndex ->
            val endExclusive = perceivedAt - weekIndex * config.weeklyReportIntervalMs
            val startInclusive = endExclusive - config.weeklyReportIntervalMs
            exerciseResults.asSequence()
                .filter { it.completedAt in startInclusive until endExclusive }
                .map { it.exerciseSessionId }
                .distinct()
                .count()
        }.reversed()
        val consecutiveLowWeeks = completedByWeek.asReversed()
            .takeWhile { it <= config.lowAdherenceMaximumCompletedSessions }
            .size

        val safetyEvents = mergeSafetyEvents(prior, exerciseResults.flatMap { result ->
            result.safetyEvents.map { type ->
                val source = "${result.resultId}:$type"
                CareSafetyEventSnapshot(
                    eventId = CareStableIds.eventId(profile.id, CareEventType.SAFETY_EVENT, source),
                    type = type,
                    occurredAt = result.completedAt,
                    active = false,
                )
            }
        }, event, perceivedAt)
        val fallReports = mergeFallReports(prior, event, perceivedAt)
        val latestCompletedAt = requireNotNull(latest.completedAt)
        val canonicalDueAt = latestCompletedAt + config.reassessmentIntervalMs
        var dueAt = prior?.reassessmentDueAt?.let { minOf(it, canonicalDueAt) } ?: canonicalDueAt
        if (event.type == CareEventType.SAFETY_EVENT || event.type == CareEventType.FALL_REPORTED) {
            dueAt = minOf(dueAt, perceivedAt)
        }
        val proposalAvailable = latest.exercisePrescription.plan?.progressionProposals
            ?.any { it.status == ProgressionStatus.PENDING_APPROVAL } == true

        return CareInputState(
            profile = CareProfileSnapshot(profile.id, profile.birthYear, profile.gender.toCanonicalSex(), profile.updatedAt),
            canonicalClinicalReference = clinical,
            recentAssessments = validSummaries,
            trend = validSummaries.toTrend(),
            adherence = CareAdherenceSnapshot(
                completedSessionsByWeek = completedByWeek,
                targetSessionsPerWeek = config.adherenceTargetSessionsPerWeek,
                consecutiveLowWeeks = consecutiveLowWeeks,
            ),
            safetyEvents = safetyEvents,
            fallReports = fallReports,
            invalidAttemptNumerator = invalidNumerator,
            invalidAttemptDenominator = invalidDenominator,
            invalidAttemptRatio = invalidRatio,
            reassessmentDueAt = dueAt,
            nextPlannedSessionAt = prior?.nextPlannedSessionAt,
            progressionEligible = proposalAvailable,
            caregiverNotificationsConsented = prior?.caregiverNotificationsConsented ?: false,
            perceivedAt = perceivedAt,
        )
    }

    private fun AssessmentSession.toClinicalReference(): CareCanonicalClinicalReference {
        val plan = exercisePrescription.plan
        val professional = plan?.professionalApproval
        return CareCanonicalClinicalReference(
            assessmentSessionId = assessmentSessionId,
            assessmentRevision = revision,
            steadiRuleVersion = steadi.ruleVersion,
            risk = steadi.risk,
            vulnerabilityRuleVersion = vulnerabilityAssessment?.ruleVersion,
            vulnerabilityIds = vulnerabilityAssessment?.activeIds?.toSet().orEmpty(),
            prescriptionPlanId = plan?.planId,
            prescriptionSchemaVersion = plan?.schemaVersion,
            professionalApprovalStatus = professional?.status ?: ApprovalStatus.NOT_REQUIRED,
            professionalApprovalId = professional?.approvalId,
        )
    }

    private fun AssessmentSession.toValidSummary(): CareAssessmentSummary? {
        val chair = functionalTests.chairStand30s.acceptedResult ?: return null
        val balance = functionalTests.fourStageBalance.acceptedResult ?: return null
        if (chair.quality?.excludeFromTrends == true || balance.quality?.excludeFromTrends == true) return null
        return CareAssessmentSummary(
            assessmentSessionId = assessmentSessionId,
            completedAt = completedAt ?: return null,
            chairStandRepetitions = chair.chairStand?.cdcScoredRepetitions ?: return null,
            tandemHoldSeconds = balance.tandemHoldSeconds ?: return null,
            valid = true,
        )
    }

    private fun List<CareAssessmentSummary>.toTrend(): CareTrendSnapshot {
        val chairStreak = trailingDecliningPointCount(map { it.chairStandRepetitions.toDouble() })
        val tandemStreak = trailingDecliningPointCount(map { it.tandemHoldSeconds })
        val streak = maxOf(chairStreak, tandemStreak)
        return CareTrendSnapshot(
            declining = streak >= config.decliningConsecutiveAssessments,
            consecutiveDeclines = streak,
        )
    }

    private fun trailingDecliningPointCount(values: List<Double>): Int {
        if (values.size < 2) return 0
        var count = 1
        for (index in values.lastIndex downTo 1) {
            if (values[index] < values[index - 1]) count += 1 else break
        }
        return count
    }

    private fun mergeSafetyEvents(
        prior: CareInputState?,
        fromResults: List<CareSafetyEventSnapshot>,
        event: CareEvent,
        perceivedAt: Long,
    ): List<CareSafetyEventSnapshot> {
        val current = if (event.type == CareEventType.SAFETY_EVENT) {
            listOf(
                CareSafetyEventSnapshot(
                    eventId = event.eventId,
                    type = requireNotNull(event.payload["safetyType"]),
                    occurredAt = perceivedAt,
                    active = true,
                ),
            )
        } else emptyList()
        return (prior?.safetyEvents.orEmpty() + fromResults + current).distinctBy { it.eventId }
    }

    private fun mergeFallReports(
        prior: CareInputState?,
        event: CareEvent,
        perceivedAt: Long,
    ): List<CareFallReportSnapshot> {
        val current = if (event.type == CareEventType.FALL_REPORTED) {
            listOf(
                CareFallReportSnapshot(
                    eventId = event.eventId,
                    occurredAt = perceivedAt,
                    injurious = event.payload.getValue("injurious").toBooleanStrict(),
                    unresolved = true,
                ),
            )
        } else emptyList()
        return (prior?.fallReports.orEmpty() + current).distinctBy { it.eventId }
    }

    private fun validateEventPayload(event: CareEvent) {
        val allowed = when (event.type) {
            CareEventType.SAFETY_EVENT -> setOf("safetyType")
            CareEventType.FALL_REPORTED -> setOf("injurious")
            else -> emptySet()
        }
        require(event.payload.keys == allowed) { "Care event contains missing or unknown payload fields" }
        if (event.type == CareEventType.SAFETY_EVENT) require(event.payload.getValue("safetyType").isNotBlank())
        if (event.type == CareEventType.FALL_REPORTED) event.payload.getValue("injurious").toBooleanStrict()
        require(event.payload.keys.none { key ->
            val normalized = key.lowercase()
            normalized.contains("risk") || normalized.contains("vulnerability") ||
                normalized.contains("prescription") || normalized.contains("plan") || normalized.contains("approval")
        }) { "Clinical write payloads are forbidden" }
    }
}

private fun String?.toCanonicalSex(): String? = when (this?.trim()?.uppercase()) {
    "MALE", "M", "MAN" -> "MALE"
    "FEMALE", "F", "WOMAN" -> "FEMALE"
    else -> null
}
