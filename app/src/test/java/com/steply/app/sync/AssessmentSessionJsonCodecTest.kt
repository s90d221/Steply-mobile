package com.steply.app.sync

import com.steply.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class AssessmentSessionJsonCodecTest {
    @Test
    fun `canonical session envelope round trips without losing identifiers`() {
        val source = assessmentFixture()

        val decoded = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(source))

        assertEquals(source, decoded)
        assertEquals("assessment-1", decoded.session.assessmentSessionId)
        assertEquals("connection-1", decoded.session.connectionSessionId)
    }

    @Test
    fun `prescription is rejected before aggregate is scored`() {
        val invalid = assessmentFixture(
            prescription = AssessmentPrescription(PrescriptionStatus.ACTIVE, otagoPlanFixture()),
        )

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.encode(invalid)
        }
    }

    @Test
    fun `completed scored aggregate accepts generated plan`() {
        val completed = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(),
            prescription = AssessmentPrescription(PrescriptionStatus.ACTIVE, otagoPlanFixture()),
        )

        val decoded = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(completed))

        assertEquals(AssessmentSessionStatus.COMPLETED, decoded.session.status)
    }

    @Test
    fun `REQ-S8-RESULT assessment envelope persists typed exercise session results`() {
        val vulnerability = VulnerabilityId.V1
        val exercise = prescribedExerciseFixture(ExerciseId.B5, vulnerability)
        val result = ExerciseSessionResult(
            resultId = "exercise-result-1",
            planId = "plan-1",
            exerciseSessionId = "exercise-session-1",
            exerciseId = ExerciseId.B5,
            variantId = exercise.variantId,
            level = exercise.level,
            source = ExerciseResultSource.LIVE_POSE,
            startedAt = 1_700_000_003_000L,
            completedAt = 1_700_000_013_000L,
            prescribedDosage = ExerciseDosage(null, 1, null, null, 10),
            completedDosage = ExerciseDosage(null, 1, null, null, 10),
            formAccurate = true,
            lowerBodyRecoveryWithoutGripping = true,
            supportUsed = false,
            safetyEvents = emptyList(),
            cameraVerification = CameraVerification.FULL,
        )
        val plan = otagoPlanFixture(vulnerabilityIds = listOf(vulnerability), selectedExercises = listOf(exercise))
        val source = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(),
            prescription = AssessmentPrescription(PrescriptionStatus.ACTIVE, plan, listOf(result)),
        ).let { envelope ->
            envelope.copy(session = envelope.session.copy(
                vulnerabilityAssessment = VulnerabilityAssessment(
                    activeIds = listOf(vulnerability),
                    evidence = listOf(VulnerabilityEvidence(vulnerability, "four_stage_balance-result", "{\"initialToStaticRatio\":1.5}")),
                ),
            ))
        }

        val restored = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(source))

        assertEquals(listOf(result), restored.session.exercisePrescription.sessionResults)
    }

    @Test
    fun `REQ-S8-PROGRESSION strength qualifying sessions require accurate 10 repetitions by 2 sets`() {
        val exercise = prescribedExerciseFixture(
            ExerciseId.S1,
            VulnerabilityId.V3,
            weightMode = WeightMode.FATIGUE_TARGET,
            weightMinKg = 1.0,
            weightMaxKg = 8.0,
        )
        val proposal = ProgressionProposal(
            proposalId = "weight-progress-1",
            exerciseId = ExerciseId.S1,
            fromLevel = exercise.level,
            toLevel = exercise.level,
            fromVariantId = exercise.variantId,
            toVariantId = exercise.variantId,
            progressionType = ProgressionType.INCREASE_WEIGHT,
            weightIncrementMinKg = 0.5,
            weightIncrementMaxKg = 1.0,
            status = ProgressionStatus.PENDING_APPROVAL,
            qualifyingSessionIds = listOf("exercise-session-1", "exercise-session-2"),
            approval = null,
        )
        val plan = otagoPlanFixture(
            vulnerabilityIds = listOf(VulnerabilityId.V3),
            selectedExercises = listOf(exercise),
            progressionProposals = listOf(proposal),
        )
        fun result(index: Int, sets: Int) = ExerciseSessionResult(
            resultId = "exercise-result-$index",
            planId = plan.planId,
            exerciseSessionId = "exercise-session-$index",
            exerciseId = exercise.exerciseId,
            variantId = exercise.variantId,
            level = exercise.level,
            source = ExerciseResultSource.LIVE_POSE,
            startedAt = 1_700_000_003_000L + index,
            completedAt = 1_700_000_013_000L + index,
            prescribedDosage = ExerciseDosage(null, 2, 10, null, null),
            completedDosage = ExerciseDosage(null, sets, 10, null, null),
            formAccurate = true,
            lowerBodyRecoveryWithoutGripping = null,
            supportUsed = false,
            safetyEvents = emptyList(),
            cameraVerification = CameraVerification.FULL,
        )
        fun envelope(results: List<ExerciseSessionResult>) = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(),
            prescription = AssessmentPrescription(PrescriptionStatus.ACTIVE, plan, results),
        ).let { source ->
            source.copy(session = source.session.copy(
                vulnerabilityAssessment = VulnerabilityAssessment(
                    listOf(VulnerabilityId.V3),
                    listOf(VulnerabilityEvidence(VulnerabilityId.V3, "chair_stand_30s-result", "{\"belowCdcCutoff\":true}")),
                ),
            ))
        }

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.encode(envelope(listOf(result(1, 2), result(2, 1))))
        }
        val valid = envelope(listOf(result(1, 2), result(2, 2)))
        assertEquals(valid, AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(valid)))
    }

    @Test
    fun `unknown canonical session field is rejected`() {
        val json = JSONObject(AssessmentSessionJsonCodec.encode(assessmentFixture()))
        json.getJSONObject("session").put("unexpected", true)

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.decode(json)
        }
    }

    @Test
    fun `REQ-S8-CONTRACT v2 prescription requires canonical sessionResults array`() {
        val json = JSONObject(AssessmentSessionJsonCodec.encode(assessmentFixture()))
        json.getJSONObject("session").getJSONObject("exercisePrescription").remove("sessionResults")

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.decode(json)
        }
    }

    @Test
    fun `REQ-S6-3 S6-5 S7-2 v2 round trip preserves calibration quality failures and sway precision`() {
        val source = assessmentFixture()
        val balance = requireNotNull(source.session.functionalTests.fourStageBalance.acceptedResult)
        val invalidResult = balance.copy(
            resultId = "balance-invalid-result",
            attemptId = "balance-invalid-attempt",
            analysisSessionId = "balance-invalid-analysis",
            status = AssessmentResultStatus.INVALID,
            quality = qualityFixture(
                g3ViolationRatio = 0.2001,
                invalidReasons = listOf("G3_VIOLATION_RATIO_EXCEEDED"),
                excludeFromTrends = true,
            ),
            balance = balance.balance?.copy(
                stages = balance.balance.stages.map { stage ->
                    if (stage.stage == BalanceStage.TANDEM) stage.copy(
                        status = BalanceStageStatus.FAILED,
                        failureCode = BalanceFailureCode.F5,
                        failureReason = "GUARDIAN_INTERVENTION",
                        sway = BalanceSwayMetrics(0.012345, 0.006789, 0.0142, 0.0081, 1.753086, 1.818383),
                    ) else stage.copy(
                        sway = BalanceSwayMetrics(
                            mlRmsM = 0.01 + stage.stage.ordinal * 0.001,
                            apRmsM = 0.02 + stage.stage.ordinal * 0.001,
                            initialRmsM = 0.03 + stage.stage.ordinal * 0.001,
                            staticRmsM = 0.04 + stage.stage.ordinal * 0.001,
                            initialToStaticRatio = 1.1 + stage.stage.ordinal * 0.1,
                            mlToApRatio = 1.2 + stage.stage.ordinal * 0.1,
                        ),
                    )
                },
            ),
        )
        val invalidAttempt = AssessmentAttemptSummary(
            attemptId = invalidResult.attemptId,
            analysisSessionId = invalidResult.analysisSessionId,
            status = AssessmentAttemptStatus.INVALID,
            startedAt = invalidResult.completedAt - 1_000L,
            completedAt = invalidResult.completedAt,
            supersedesAttemptId = null,
            resultHash = invalidResult.resultHash,
            result = invalidResult,
        )
        val envelope = source.copy(
            session = source.session.copy(
                functionalTests = source.session.functionalTests.copy(
                    fourStageBalance = source.session.functionalTests.fourStageBalance.copy(
                        attempts = listOf(invalidAttempt) + source.session.functionalTests.fourStageBalance.attempts,
                    ),
                ),
            ),
        )

        val decoded = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(envelope))
        val restored = decoded.session.functionalTests.fourStageBalance.attempts.first().result

        assertEquals(0.24, restored?.calibration?.lFootM ?: -1.0, 0.0)
        assertEquals(0.2001, restored?.quality?.g3ViolationRatio ?: -1.0, 0.0)
        assertEquals(true, restored?.quality?.excludeFromTrends)
        assertEquals(BalanceFailureCode.F5, restored?.balance?.stages?.first { it.stage == BalanceStage.TANDEM }?.failureCode)
        assertEquals("GUARDIAN_INTERVENTION", restored?.balance?.stages?.first { it.stage == BalanceStage.TANDEM }?.failureReason)
        assertEquals(0.012345, restored?.balance?.stages?.first { it.stage == BalanceStage.TANDEM }?.sway?.mlRmsM ?: -1.0, 0.0)
        assertEquals(invalidResult.balance, restored?.balance)
    }

    @Test
    fun `REQ-S7-1 second arm use keeps CDC zero and V6 evidence`() {
        val source = assessmentFixture(armUse = true)
        val decoded = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(source))
        val result = requireNotNull(decoded.session.functionalTests.chairStand30s.acceptedResult)
        val chair = requireNotNull(result.chairStand)

        assertEquals(0, chair.cdcScoredRepetitions)
        assertEquals(ArmUseOutcome.DISQUALIFIED, chair.armUse.outcome)
        assertEquals(listOf(VulnerabilityId.V6), decoded.session.vulnerabilityAssessment?.activeIds)
        assertEquals(listOf(VulnerabilityId.V6), result.vulnerabilityAssessment?.activeIds)
        assertEquals(result.resultId, result.vulnerabilityAssessment?.evidence?.single()?.sourceResultId)
    }

    @Test
    fun `REQ-S6-5 G3 ratio above 20 percent requires INVALID and trend exclusion`() {
        val source = assessmentFixture()
        val validChair = requireNotNull(source.session.functionalTests.chairStand30s.acceptedResult)
        val atBoundary = source.copy(session = source.session.copy(
            functionalTests = source.session.functionalTests.copy(
                chairStand30s = source.session.functionalTests.chairStand30s.copy(
                    acceptedResult = validChair.copy(quality = qualityFixture(g3ViolationRatio = 0.20)),
                ),
            ),
        ))
        AssessmentSessionJsonCodec.encode(atBoundary)

        val aboveBoundary = atBoundary.copy(session = atBoundary.session.copy(
            functionalTests = atBoundary.session.functionalTests.copy(
                chairStand30s = atBoundary.session.functionalTests.chairStand30s.copy(
                    acceptedResult = validChair.copy(quality = qualityFixture(g3ViolationRatio = 0.2001)),
                ),
            ),
        ))

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.encode(aboveBoundary)
        }
    }

    @Test
    fun `REQ-S7-1 first arm use persists one restart without V6`() {
        val source = assessmentFixture()
        val validChair = requireNotNull(source.session.functionalTests.chairStand30s.acceptedResult)
        val restartResult = validChair.copy(
            resultId = "chair-restart-result",
            attemptId = "chair-restart-attempt",
            analysisSessionId = "chair-restart-analysis",
            status = AssessmentResultStatus.INVALID,
            quality = qualityFixture(
                invalidReasons = listOf("ARM_USE_RESTART_REQUIRED"),
                excludeFromTrends = true,
            ),
            vulnerabilityAssessment = null,
            chairStand = requireNotNull(validChair.chairStand).copy(
                cdcScoredRepetitions = 0,
                armUse = ChairStandArmUse(1, restartUsed = true, outcome = ArmUseOutcome.RESTART_REQUIRED),
            ),
        )
        val restartAttempt = AssessmentAttemptSummary(
            attemptId = restartResult.attemptId,
            analysisSessionId = restartResult.analysisSessionId,
            status = AssessmentAttemptStatus.INVALID,
            startedAt = restartResult.completedAt - 1_000L,
            completedAt = restartResult.completedAt,
            supersedesAttemptId = null,
            resultHash = restartResult.resultHash,
            result = restartResult,
        )
        val envelope = source.copy(session = source.session.copy(
            functionalTests = source.session.functionalTests.copy(
                chairStand30s = source.session.functionalTests.chairStand30s.copy(
                    attempts = listOf(restartAttempt) + source.session.functionalTests.chairStand30s.attempts,
                ),
            ),
        ))

        val restored = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(envelope))
            .session.functionalTests.chairStand30s.attempts.first().result

        assertEquals(ArmUseOutcome.RESTART_REQUIRED, restored?.chairStand?.armUse?.outcome)
        assertEquals(1, restored?.chairStand?.armUse?.occurrenceCount)
        assertEquals(true, restored?.chairStand?.armUse?.restartUsed)
        assertEquals(null, restored?.vulnerabilityAssessment)
    }

    @Test
    fun `REQ-S7-1 disqualified arm use rejects missing result V6 evidence`() {
        val source = assessmentFixture(armUse = true)
        val chairSlot = source.session.functionalTests.chairStand30s
        val withoutV6 = source.copy(session = source.session.copy(
            functionalTests = source.session.functionalTests.copy(
                chairStand30s = chairSlot.copy(
                    acceptedResult = requireNotNull(chairSlot.acceptedResult).copy(vulnerabilityAssessment = null),
                ),
            ),
        ))

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.encode(withoutV6)
        }
    }

    @Test
    fun `REQ-S7-2 support ROI round trips and must match balance calibration`() {
        val source = assessmentFixture()
        val restored = AssessmentSessionJsonCodec.decode(AssessmentSessionJsonCodec.encode(source)).session
        val expectedRoi = source.session.operationalContext?.supportRoiNormalized

        assertEquals(expectedRoi, restored.operationalContext?.supportRoiNormalized)
        assertEquals(expectedRoi, restored.functionalTests.fourStageBalance.acceptedResult?.calibration?.supportRoiNormalized)
        assertEquals(null, restored.functionalTests.chairStand30s.acceptedResult?.calibration?.supportRoiNormalized)

        val balanceSlot = source.session.functionalTests.fourStageBalance
        val mismatch = source.copy(session = source.session.copy(
            functionalTests = source.session.functionalTests.copy(
                fourStageBalance = balanceSlot.copy(
                    acceptedResult = requireNotNull(balanceSlot.acceptedResult).copy(
                        calibration = requireNotNull(balanceSlot.acceptedResult.calibration).copy(
                            supportRoiNormalized = NormalizedRoi(0.6, 0.2, 0.2, 0.5),
                        ),
                    ),
                ),
            ),
        ))

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.encode(mismatch)
        }
    }

    @Test
    fun `REQ-S7-2 balance failureCode is limited to F1 through F5 and reason is separate`() {
        val json = JSONObject(AssessmentSessionJsonCodec.encode(assessmentFixture()))
        val tandem = json.getJSONObject("session")
            .getJSONObject("functionalTests")
            .getJSONObject("FOUR_STAGE_BALANCE")
            .getJSONObject("acceptedResult")
            .getJSONObject("balance")
            .getJSONArray("stages")
            .getJSONObject(BalanceStage.TANDEM.ordinal)
        tandem.put("failureCode", "F2_POSITION_LOST")
        tandem.put("failureReason", "POSITION_LOST")

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.decode(json)
        }
    }

    @Test
    fun `REQ-S6-5 unknown nested result field is rejected instead of silently dropped`() {
        val json = JSONObject(AssessmentSessionJsonCodec.encode(assessmentFixture()))
        json.getJSONObject("session")
            .getJSONObject("functionalTests")
            .getJSONObject("CHAIR_STAND_30S")
            .getJSONObject("acceptedResult")
            .put("unversionedMetric", 1)

        assertThrows(AssessmentSessionContractException::class.java) {
            AssessmentSessionJsonCodec.decode(json)
        }
    }

    @Test
    fun `REQ-SCHEMA-V1 persisted v1 snapshot remains decodable and re-encodes as v1`() {
        val v2 = assessmentFixture()
        fun legacy(result: AcceptedAssessmentResult?) = result?.copy(
            resultSchemaVersion = null,
            resultHash = null,
            operationalConfigVersion = null,
            calibration = null,
            quality = null,
            vulnerabilityAssessment = null,
            chairStand = null,
            balance = null,
            legacyCompletedRepetitions = result.completedRepetitions,
            legacyArmUseConfirmed = result.armUseConfirmed,
            legacyTandemHoldSeconds = result.tandemHoldSeconds,
            legacySideBySideHoldSeconds = result.sideBySideHoldSeconds,
            legacySemiTandemHoldSeconds = result.semiTandemHoldSeconds,
            legacyOneLegHoldSeconds = result.oneLegHoldSeconds,
        )
        fun legacySlot(slot: AssessmentTestSlot) = slot.copy(
            acceptedResult = legacy(slot.acceptedResult),
            attempts = slot.attempts.map { it.copy(resultHash = null, result = null) },
        )
        val legacyEnvelope = v2.copy(session = v2.session.copy(
            schemaVersion = LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION,
            operationalContext = null,
            functionalTests = AssessmentFunctionalTests(
                legacySlot(v2.session.functionalTests.fourStageBalance),
                legacySlot(v2.session.functionalTests.chairStand30s),
            ),
            vulnerabilityAssessment = null,
        ))

        val encoded = AssessmentSessionJsonCodec.encode(legacyEnvelope)
        val decoded = AssessmentSessionJsonCodec.decode(encoded)

        assertEquals(LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION, decoded.session.schemaVersion)
        assertEquals(12, decoded.session.functionalTests.chairStand30s.acceptedResult?.completedRepetitions)
        assertEquals(10.0, decoded.session.functionalTests.fourStageBalance.acceptedResult?.tandemHoldSeconds ?: -1.0, 0.0)
        assertEquals(LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION, JSONObject(AssessmentSessionJsonCodec.encode(decoded)).getString("schemaVersion"))
    }
}
