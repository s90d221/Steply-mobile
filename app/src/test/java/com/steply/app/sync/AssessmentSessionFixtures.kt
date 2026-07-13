package com.steply.app.sync

import com.steply.app.domain.model.*

internal fun assessmentFixture(
    revision: Long = 1L,
    sessionStatus: AssessmentSessionStatus = AssessmentSessionStatus.IN_PROGRESS,
    screeningStatus: AssessmentSlotStatus = AssessmentSlotStatus.COMPLETED,
    q1: Boolean? = false,
    q2: Boolean? = false,
    q3: Boolean? = false,
    fallCount: AssessmentFallCount? = AssessmentFallCount.ZERO,
    injuriousFall: Boolean? = null,
    chairCompleted: Boolean = true,
    chairRepetitions: Int = 12,
    armUse: Boolean = false,
    balanceCompleted: Boolean = true,
    tandemSeconds: Double = 10.0,
    steadi: SteadiScore = notScorableFixture(),
    prescription: AssessmentPrescription = AssessmentPrescription(PrescriptionStatus.NOT_GENERATED, null),
): AssessmentSessionEnvelope {
    val chairSlot = testSlot(
        type = AssessmentType.CHAIR_STAND_30S,
        completed = chairCompleted,
        repetitions = chairRepetitions,
        armUse = armUse,
    )
    val balanceSlot = testSlot(
        type = AssessmentType.FOUR_STAGE_BALANCE,
        completed = balanceCompleted,
        tandemSeconds = tandemSeconds,
    )
    return AssessmentSessionEnvelope(
        messageId = "message-$revision",
        baseRevision = (revision - 1L).coerceAtLeast(0L),
        session = AssessmentSession(
            assessmentSessionId = "assessment-1",
            connectionSessionId = "connection-1",
            profileId = "profile-1",
            revision = revision,
            status = sessionStatus,
            screening = AssessmentScreening(screeningStatus, q1, q2, q3, fallCount, injuriousFall),
            profileSnapshot = AssessmentProfileSnapshot(1956, 70, AssessmentSex.MALE),
            operationalContext = AssessmentOperationalContext(
                STAGE2_OPERATIONAL_CONFIG_VERSION,
                NormalizedRoi(0.05, 0.1, 0.25, 0.9),
            ),
            functionalTests = AssessmentFunctionalTests(balanceSlot, chairSlot),
            vulnerabilityAssessment = chairSlot.acceptedResult?.vulnerabilityAssessment
                ?: VulnerabilityAssessment(emptyList(), emptyList()),
            steadi = steadi,
            exercisePrescription = prescription,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L + revision,
            completedAt = if (sessionStatus == AssessmentSessionStatus.COMPLETED) 1_700_000_001_000L else null,
        ),
    )
}

internal fun scoredFixture(risk: SteadiRisk = SteadiRisk.LOW) = SteadiScore(
    status = SteadiStatus.SCORED,
    risk = risk,
    strengthProblem = false,
    balanceProblem = false,
    step1AtRisk = false,
    step2Problem = false,
    reasonCodes = emptyList(),
    ruleVersion = STEADI_RULE_VERSION,
)

internal fun otagoPlanFixture(
    risk: SteadiRisk = SteadiRisk.LOW,
    vulnerabilityIds: List<VulnerabilityId> = emptyList(),
    selectedExercises: List<PrescribedExercise> = emptyList(),
    status: OtagoPlanStatus = if (risk == SteadiRisk.HIGH) OtagoPlanStatus.PENDING_PROFESSIONAL_REVIEW else OtagoPlanStatus.ACTIVE,
    approvedHigh: Boolean = false,
    progressionProposals: List<ProgressionProposal> = emptyList(),
) = OtagoPrescriptionPlan(
    planId = "plan-1",
    userId = "profile-1",
    riskLevel = risk,
    status = status,
    vulnerabilityIds = vulnerabilityIds,
    warmups = OTAGO_WARMUP_IDS.map(::warmupFixture),
    selectedExercises = selectedExercises,
    walkingPlan = if (risk == SteadiRisk.HIGH) null else walkingFixture(),
    professionalApproval = when {
        risk != SteadiRisk.HIGH -> ProfessionalApproval(ApprovalStatus.NOT_REQUIRED, null, null, null)
        approvedHigh -> ProfessionalApproval(ApprovalStatus.APPROVED, "professional-approval-1", ApprovalActorRole.PROFESSIONAL, 1_700_000_002_000L)
        else -> ProfessionalApproval(ApprovalStatus.PENDING, null, null, null)
    },
    supervisionRequirement = when (risk) {
        SteadiRisk.LOW -> SupervisionRequirement.NONE
        SteadiRisk.MODERATE -> SupervisionRequirement.CAREGIVER_RECOMMENDED
        SteadiRisk.HIGH -> SupervisionRequirement.PROFESSIONAL_REVIEW_REQUIRED
        SteadiRisk.NOT_SCORABLE -> SupervisionRequirement.NONE
    },
    caregiverRecommendedDays = if (risk == SteadiRisk.MODERATE) 14 else 0,
    requiresProfessionalReview = risk == SteadiRisk.HIGH && !approvedHigh,
    safetyNotices = listOf("STOP_ON_PAIN_DIZZINESS_OR_DISTRESS"),
    progressionProposals = progressionProposals,
    sourceAssessmentIds = listOf("assessment-1"),
    sourceResultIds = listOf("four_stage_balance-result", "chair_stand_30s-result"),
    generatedByRuleVersion = "deterministic_otago_engine.v1",
    decisionTrace = listOf("DETERMINISTIC_VULNERABILITY_MAPPING_APPLIED"),
)

internal fun warmupFixture(id: ExerciseId) = PrescribedExercise(
    exerciseId = id,
    category = ExerciseCategory.WARMUP,
    level = ExerciseLevel.A,
    variantId = "${id.name}-A",
    displayName = "Warm-up ${id.name}",
    repetitions = if (id == ExerciseId.W5) null else 5,
    sets = 1,
    repetitionsPerSide = if (id == ExerciseId.W5) 10 else null,
    steps = null,
    holdSeconds = null,
    supportRequirement = SupportRequirement.NONE,
    weightMode = WeightMode.NONE,
    weightMinKg = null,
    weightMaxKg = null,
    tempoUpMinSeconds = null,
    tempoUpMaxSeconds = null,
    tempoDownMinSeconds = null,
    tempoDownMaxSeconds = null,
    breathingRule = "Breathe normally.",
    restMinSeconds = null,
    restMaxSeconds = null,
    cameraVerification = CameraVerification.MANUAL_ONLY,
    reasonVulnerabilityIds = emptyList(),
    weakSideExtraSets = 0,
)

internal fun prescribedExerciseFixture(
    id: ExerciseId,
    reason: VulnerabilityId,
    level: ExerciseLevel = ExerciseLevel.A,
    weightMode: WeightMode = WeightMode.NONE,
    weightMinKg: Double? = null,
    weightMaxKg: Double? = null,
) = PrescribedExercise(
    exerciseId = id,
    category = id.expectedCategory(),
    level = level,
    variantId = "${id.name}-${level.name}",
    displayName = "Exercise ${id.name}",
    repetitions = when (id.expectedCategory()) {
        ExerciseCategory.STRENGTH -> if (id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) null else 10
        ExerciseCategory.BALANCE -> if (id in listOf(ExerciseId.B1, ExerciseId.B11)) 10 else null
        else -> 5
    },
    sets = if (id in listOf(ExerciseId.S4, ExerciseId.S5)) 2 else 1,
    repetitionsPerSide = if (id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) 10 else null,
    steps = if (id in listOf(ExerciseId.B2, ExerciseId.B3, ExerciseId.B4, ExerciseId.B6, ExerciseId.B8, ExerciseId.B9, ExerciseId.B10, ExerciseId.B12)) 10 else null,
    holdSeconds = if (id in listOf(ExerciseId.B5, ExerciseId.B7)) 10 else null,
    supportRequirement = if (id.expectedCategory() == ExerciseCategory.BALANCE) SupportRequirement.STABLE_SUPPORT else SupportRequirement.NONE,
    weightMode = weightMode,
    weightMinKg = weightMinKg,
    weightMaxKg = weightMaxKg,
    tempoUpMinSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 2.0 else null,
    tempoUpMaxSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 3.0 else null,
    tempoDownMinSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 4.0 else null,
    tempoDownMaxSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 5.0 else null,
    breathingRule = "Never hold your breath.",
    restMinSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 60 else null,
    restMaxSeconds = if (id.expectedCategory() == ExerciseCategory.STRENGTH) 120 else null,
    cameraVerification = when (id) {
        in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3, ExerciseId.S4, ExerciseId.S5, ExerciseId.B1, ExerciseId.B5, ExerciseId.B7, ExerciseId.B11) -> CameraVerification.FULL
        ExerciseId.B4 -> CameraVerification.PARTIAL
        else -> CameraVerification.MANUAL_ONLY
    },
    reasonVulnerabilityIds = listOf(reason),
    weakSideExtraSets = if (reason == VulnerabilityId.V9 && id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) 1 else 0,
)

internal fun walkingFixture() = WalkingPlan(
    exerciseId = ExerciseId.WALK,
    category = ExerciseCategory.WALKING,
    targetMinutes = 30,
    splitMinutes = listOf(10, 10, 10),
    weeklyFrequency = 2,
    pace = "USUAL",
    requiresStrengthAndBalance = true,
    cameraVerification = CameraVerification.MANUAL_ONLY,
)

internal fun notScorableFixture() = SteadiScore(
    status = SteadiStatus.NOT_SCORABLE,
    risk = SteadiRisk.NOT_SCORABLE,
    strengthProblem = null,
    balanceProblem = null,
    step1AtRisk = null,
    step2Problem = null,
    reasonCodes = listOf("ASSESSMENT_INCOMPLETE"),
    ruleVersion = STEADI_RULE_VERSION,
)

private fun testSlot(
    type: AssessmentType,
    completed: Boolean,
    repetitions: Int = 12,
    armUse: Boolean = false,
    tandemSeconds: Double = 10.0,
): AssessmentTestSlot {
    if (!completed) return AssessmentTestSlot(AssessmentSlotStatus.NOT_STARTED, null, null, emptyList())
    val attemptId = "${type.name.lowercase()}-attempt"
    val analysisId = "${type.name.lowercase()}-analysis"
    val resultId = "${type.name.lowercase()}-result"
    val attempt = AssessmentAttemptSummary(
        attemptId,
        analysisId,
        AssessmentAttemptStatus.VALID,
        1_700_000_000_000L,
        1_700_000_000_500L,
        null,
    )
    val result = AcceptedAssessmentResult(
        resultSchemaVersion = STAGE2_RESULT_SCHEMA_VERSION,
        resultId = resultId,
        resultHash = "a".repeat(64),
        attemptId = attemptId,
        analysisSessionId = analysisId,
        assessmentType = type,
        status = AssessmentResultStatus.VALID,
        source = "LIVE_POSE",
        completedAt = 1_700_000_000_500L,
        operationalConfigVersion = STAGE2_OPERATIONAL_CONFIG_VERSION,
        calibration = AssessmentCalibration(
            sampledDurationMs = 3_000L,
            lFootM = 0.24,
            hStandM = 0.93,
            hSitM = if (type == AssessmentType.CHAIR_STAND_30S) 0.51 else null,
            wShoulderM = 0.42,
            dFoldM = if (type == AssessmentType.CHAIR_STAND_30S) 0.18 else null,
            supportRoiNormalized = if (type == AssessmentType.FOUR_STAGE_BALANCE) {
                NormalizedRoi(0.05, 0.1, 0.25, 0.9)
            } else null,
        ),
        quality = qualityFixture(),
        vulnerabilityAssessment = if (armUse) {
            VulnerabilityAssessment(
                activeIds = listOf(VulnerabilityId.V6),
                evidence = listOf(
                    VulnerabilityEvidence(
                        vulnerabilityId = VulnerabilityId.V6,
                        sourceResultId = resultId,
                        measurementsJson = "{\"armUseOccurrenceCount\":2,\"officialScore\":0}",
                    ),
                ),
            )
        } else null,
        chairStand = if (type == AssessmentType.CHAIR_STAND_30S) {
            ChairStandMeasurements(
                observedRepetitions = repetitions,
                completedRepetitions = repetitions,
                cdcScoredRepetitions = if (armUse) 0 else repetitions,
                finalRepetitionCredit = 0,
                finalState = ChairStandFinalState.SIT,
                armUse = ChairStandArmUse(
                    occurrenceCount = if (armUse) 2 else 0,
                    restartUsed = armUse,
                    outcome = if (armUse) ArmUseOutcome.DISQUALIFIED else ArmUseOutcome.NOT_DETECTED,
                ),
            )
        } else null,
        balance = if (type == AssessmentType.FOUR_STAGE_BALANCE) {
            BalanceMeasurements(
                stages = BalanceStage.entries.map { stage ->
                    BalanceStageResult(
                        stage = stage,
                        onsetLatencyMs = 500L,
                        holdSeconds = if (stage == BalanceStage.TANDEM) tandemSeconds else 10.0,
                        status = BalanceStageStatus.PASSED,
                        failureCode = null,
                        failureReason = null,
                        sway = BalanceSwayMetrics(0.0123, 0.0087, 0.015, 0.01, 1.5, 1.41379),
                    )
                },
            )
        } else null,
    )
    return AssessmentTestSlot(AssessmentSlotStatus.COMPLETED, attemptId, result, listOf(attempt))
}

internal fun qualityFixture(
    g3ViolationRatio: Double = 0.0,
    invalidReasons: List<String> = emptyList(),
    excludeFromTrends: Boolean = false,
) = AssessmentQualitySummary(
    gates = QualityGateId.entries.map { gate ->
        QualityGateObservation(
            gate = gate,
            violationFrameCount = if (gate == QualityGateId.G3 && g3ViolationRatio > 0.0) 1 else 0,
            violationDurationMs = if (gate == QualityGateId.G3 && g3ViolationRatio > 0.0) 500L else 0L,
            violationRatio = if (gate == QualityGateId.G3) g3ViolationRatio else 0.0,
        )
    },
    g3ViolationRatio = g3ViolationRatio,
    invalidReasons = invalidReasons,
    excludeFromTrends = excludeFromTrends,
)
