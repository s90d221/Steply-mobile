package com.steply.app.domain.steadi

import com.steply.app.domain.model.AssessmentFallCount
import com.steply.app.domain.model.AssessmentSession
import com.steply.app.domain.model.AssessmentSex
import com.steply.app.domain.model.AssessmentSlotStatus
import com.steply.app.domain.model.AssessmentType
import com.steply.app.domain.model.STEADI_RULE_VERSION
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.SteadiScore
import com.steply.app.domain.model.SteadiStatus

/** Deterministic reference implementation for contract verification. Web remains scoring authority. */
object SteadiScorer {
    const val TANDEM_CUTOFF_SECONDS = 10.0
    fun score(session: AssessmentSession): SteadiScore {
        val missing = completenessReasons(session)
        if (missing.isNotEmpty()) return notScorable(missing)

        val screening = session.screening
        val chair = requireNotNull(session.functionalTests.chairStand30s.acceptedResult)
        val balance = requireNotNull(session.functionalTests.fourStageBalance.acceptedResult)
        val age = requireNotNull(session.profileSnapshot.ageYears)
        val sex = requireNotNull(session.profileSnapshot.sex)
        val repetitions = requireNotNull(chair.completedRepetitions)
        val armUse = requireNotNull(chair.armUseConfirmed)
        val tandem = requireNotNull(balance.tandemHoldSeconds)
        val fallCount = requireNotNull(screening.fallCount)
        val cutoff = chairStandCutoff(age, sex) ?: return notScorable(listOf("PROFILE_AGE_OUTSIDE_CDC_RANGE"))
        val strengthProblem = (if (armUse) 0 else repetitions) < cutoff
        val balanceProblem = tandem < TANDEM_CUTOFF_SECONDS
        val step1 = requireNotNull(screening.fallenPastYear) ||
            requireNotNull(screening.feelsUnsteady) ||
            requireNotNull(screening.worriedAboutFalling)
        val step2 = strengthProblem || balanceProblem
        val risk = when {
            !step1 || !step2 -> SteadiRisk.LOW
            screening.injuriousFall == true || fallCount == AssessmentFallCount.TWO_OR_MORE -> SteadiRisk.HIGH
            else -> SteadiRisk.MODERATE
        }
        return SteadiScore(
            status = SteadiStatus.SCORED,
            risk = risk,
            strengthProblem = strengthProblem,
            balanceProblem = balanceProblem,
            step1AtRisk = step1,
            step2Problem = step2,
            reasonCodes = emptyList(),
            ruleVersion = STEADI_RULE_VERSION,
        )
    }

    fun chairStandCutoff(ageYears: Int, sex: AssessmentSex): Int? = when (ageYears) {
        in 60..64 -> if (sex == AssessmentSex.MALE) 14 else 12
        in 65..69 -> if (sex == AssessmentSex.MALE) 12 else 11
        in 70..74 -> if (sex == AssessmentSex.MALE) 12 else 10
        in 75..79 -> if (sex == AssessmentSex.MALE) 11 else 10
        in 80..84 -> if (sex == AssessmentSex.MALE) 10 else 9
        in 85..89 -> 8
        in 90..94 -> if (sex == AssessmentSex.MALE) 7 else 4
        else -> null
    }

    private fun completenessReasons(session: AssessmentSession): List<String> {
        val reasons = mutableListOf<String>()
        val screening = session.screening
        if (screening.status != AssessmentSlotStatus.COMPLETED) reasons += "SCREENING_NOT_COMPLETED"
        if (screening.fallenPastYear == null) reasons += "SCREENING_FALLEN_MISSING"
        if (screening.feelsUnsteady == null) reasons += "SCREENING_UNSTEADY_MISSING"
        if (screening.worriedAboutFalling == null) reasons += "SCREENING_WORRY_MISSING"
        if (screening.fallCount == null) reasons += "FALL_COUNT_MISSING"
        if (screening.fallenPastYear == true && screening.injuriousFall == null) reasons += "INJURIOUS_FALL_MISSING"
        val age = session.profileSnapshot.ageYears
        val sex = session.profileSnapshot.sex
        if (age == null) reasons += "PROFILE_AGE_MISSING"
        if (sex == null) reasons += "PROFILE_SEX_MISSING"
        if (age != null && sex != null && chairStandCutoff(age, sex) == null) reasons += "PROFILE_AGE_OUTSIDE_CDC_RANGE"
        val chairSlot = session.functionalTests.chairStand30s
        val chair = chairSlot.acceptedResult
        if (chairSlot.status != AssessmentSlotStatus.COMPLETED) reasons += "CHAIR_STAND_NOT_COMPLETED"
        if (chair == null || chair.assessmentType != AssessmentType.CHAIR_STAND_30S) reasons += "CHAIR_STAND_MISSING"
        else {
            val completedRepetitions = chair.completedRepetitions
            if (completedRepetitions == null || completedRepetitions < 0) reasons += "CHAIR_REPETITIONS_INVALID"
            if (chair.armUseConfirmed == null) reasons += "CHAIR_ARM_USE_MISSING"
        }
        val balanceSlot = session.functionalTests.fourStageBalance
        val balance = balanceSlot.acceptedResult
        if (balanceSlot.status != AssessmentSlotStatus.COMPLETED) reasons += "BALANCE_NOT_COMPLETED"
        if (balance == null || balance.assessmentType != AssessmentType.FOUR_STAGE_BALANCE) reasons += "BALANCE_MISSING"
        else {
            val tandemHoldSeconds = balance.tandemHoldSeconds
            if (tandemHoldSeconds == null || !tandemHoldSeconds.isFinite() || tandemHoldSeconds !in 0.0..10.0) {
                reasons += "TANDEM_HOLD_INVALID"
            }
        }
        return reasons.distinct()
    }

    private fun notScorable(reasons: List<String>) = SteadiScore(
        status = SteadiStatus.NOT_SCORABLE,
        risk = SteadiRisk.NOT_SCORABLE,
        strengthProblem = null,
        balanceProblem = null,
        step1AtRisk = null,
        step2Problem = null,
        reasonCodes = reasons.ifEmpty { listOf("ASSESSMENT_INCOMPLETE") },
        ruleVersion = STEADI_RULE_VERSION,
    )
}
