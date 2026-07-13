package com.steply.app.sync

import com.steply.app.domain.model.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.steply.app.ui.screens.remote.parseRecommendedExercisePlan

class OtagoPrescriptionContractTest {
    @Test
    fun `REQ-S8-CATALOG canonical plan round trip preserves all W S B and WALK IDs without a count cap`() {
        val vulnerability = VulnerabilityId.V1
        val selected = ExerciseId.entries
            .filter { it.expectedCategory() in setOf(ExerciseCategory.STRENGTH, ExerciseCategory.BALANCE) }
            .map { id ->
                prescribedExerciseFixture(
                    id = id,
                    reason = vulnerability,
                    level = if (id in setOf(ExerciseId.S4, ExerciseId.S5)) ExerciseLevel.C else ExerciseLevel.A,
                    weightMode = if (id.expectedCategory() == ExerciseCategory.STRENGTH && id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) WeightMode.FATIGUE_TARGET else WeightMode.NONE,
                    weightMinKg = if (id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) 1.0 else null,
                    weightMaxKg = if (id in listOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) 8.0 else null,
                )
            }
        val plan = otagoPlanFixture(vulnerabilityIds = listOf(vulnerability), selectedExercises = selected)

        val restored = OtagoPrescriptionContract.decode(OtagoPrescriptionContract.encode(plan))

        assertEquals(plan, restored)
        assertEquals(22, restored.warmups.size + restored.selectedExercises.size)
        assertEquals(ExerciseId.entries.toSet(), (restored.warmups.map { it.exerciseId } + restored.selectedExercises.map { it.exerciseId } + restored.walkingPlan!!.exerciseId).toSet())
    }

    @Test
    fun `REQ-S7-3 V1 through V9 IDs round trip in canonical order`() {
        val vulnerabilities = VulnerabilityId.entries
        val selected = listOf(
            prescribedExerciseFixture(ExerciseId.S1, VulnerabilityId.V6),
            prescribedExerciseFixture(ExerciseId.B11, VulnerabilityId.V7),
        )
        val plan = otagoPlanFixture(
            risk = SteadiRisk.HIGH,
            vulnerabilityIds = vulnerabilities,
            selectedExercises = selected,
            status = OtagoPlanStatus.ACTIVE,
            approvedHigh = true,
        )

        val restored = OtagoPrescriptionContract.decode(OtagoPrescriptionContract.encode(plan))

        assertEquals(vulnerabilities, restored.vulnerabilityIds)
    }

    @Test
    fun `REQ-S8-HIGH pending plan preserves capped content but cannot become active before professional approval`() {
        val selected = listOf(prescribedExerciseFixture(ExerciseId.B11, VulnerabilityId.V6))
        val pending = otagoPlanFixture(
            risk = SteadiRisk.HIGH,
            vulnerabilityIds = listOf(VulnerabilityId.V6),
            selectedExercises = selected,
        )
        OtagoPrescriptionContract.validate(pending)

        val invalidActive = pending.copy(status = OtagoPlanStatus.ACTIVE)
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(invalidActive)
        }

        val approved = pending.copy(
            status = OtagoPlanStatus.ACTIVE,
            requiresProfessionalReview = false,
            professionalApproval = ProfessionalApproval(
                ApprovalStatus.APPROVED,
                "approval-1",
                ApprovalActorRole.PROFESSIONAL,
                1_700_000_010_000L,
            ),
        )
        OtagoPrescriptionContract.validate(approved)
        assertEquals(selected, approved.selectedExercises)
        assertEquals(null, approved.walkingPlan)

        val session = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(SteadiRisk.HIGH),
            prescription = AssessmentPrescription(PrescriptionStatus.PENDING_PROFESSIONAL_REVIEW, pending),
        ).session.copy(
            vulnerabilityAssessment = VulnerabilityAssessment(
                activeIds = listOf(VulnerabilityId.V6),
                evidence = listOf(VulnerabilityEvidence(VulnerabilityId.V6, "chair_stand_30s-result", "{\"officialScore\":0}")),
            ),
        )
        val mobilePlan = requireNotNull(parseRecommendedExercisePlan(session))
        assertTrue(mobilePlan.exerciseStartBlocked)
        assertEquals(selected.size + OTAGO_WARMUP_IDS.size, mobilePlan.exercises.size)
    }

    @Test
    fun `REQ-S8-HIGH-WALK Web HIGH plan requires null walking before and after approval`() {
        val pending = otagoPlanFixture(
            risk = SteadiRisk.HIGH,
            vulnerabilityIds = listOf(VulnerabilityId.V6),
            selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.B11, VulnerabilityId.V6)),
        )

        OtagoPrescriptionContract.validate(pending)
        assertEquals(null, pending.walkingPlan)
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(pending.copy(walkingPlan = walkingFixture()))
        }

        val approved = pending.copy(
            status = OtagoPlanStatus.ACTIVE,
            requiresProfessionalReview = false,
            professionalApproval = ProfessionalApproval(
                ApprovalStatus.APPROVED,
                "approval-1",
                ApprovalActorRole.PROFESSIONAL,
                1_700_000_010_000L,
            ),
        )
        OtagoPrescriptionContract.validate(approved)
        assertEquals(null, approved.walkingPlan)
    }

    @Test
    fun `REQ-S8-HIGH-CAP allows supported S4 S5 Level C but keeps S1 S3 and balance at A`() {
        val supportedS4 = prescribedExerciseFixture(ExerciseId.S4, VulnerabilityId.V1, ExerciseLevel.C).copy(
            supportRequirement = SupportRequirement.STABLE_SUPPORT,
        )
        val supportedS5 = prescribedExerciseFixture(ExerciseId.S5, VulnerabilityId.V1, ExerciseLevel.C).copy(
            supportRequirement = SupportRequirement.STABLE_SUPPORT,
        )
        val valid = otagoPlanFixture(
            risk = SteadiRisk.HIGH,
            vulnerabilityIds = listOf(VulnerabilityId.V1),
            selectedExercises = listOf(
                supportedS4,
                supportedS5,
                prescribedExerciseFixture(ExerciseId.S1, VulnerabilityId.V1),
                prescribedExerciseFixture(ExerciseId.B5, VulnerabilityId.V1),
            ),
        )

        OtagoPrescriptionContract.validate(valid)
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(valid.copy(selectedExercises = listOf(supportedS4.copy(supportRequirement = SupportRequirement.NONE))))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(valid.copy(selectedExercises = listOf(supportedS4.copy(level = ExerciseLevel.A))))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(valid.copy(selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.S1, VulnerabilityId.V1, ExerciseLevel.C))))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(valid.copy(selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.B5, VulnerabilityId.V1, ExerciseLevel.B))))
        }
    }

    @Test
    fun `REQ-S8-BLOCKED preserves canonical warmups while selected walking and progression execution stay blocked`() {
        val blocked = otagoPlanFixture(status = OtagoPlanStatus.BLOCKED).copy(walkingPlan = null)

        OtagoPrescriptionContract.validate(blocked)
        val session = assessmentFixture(
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(SteadiRisk.LOW),
            prescription = AssessmentPrescription(PrescriptionStatus.BLOCKED, blocked),
        ).session
        val mobilePlan = requireNotNull(parseRecommendedExercisePlan(session))
        assertTrue(mobilePlan.exerciseStartBlocked)
        assertEquals(OTAGO_WARMUP_IDS, mobilePlan.exercises.map { ExerciseId.valueOf(it.id) })

        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(blocked.copy(walkingPlan = walkingFixture()))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(blocked.copy(
                vulnerabilityIds = listOf(VulnerabilityId.V1),
                selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.S4, VulnerabilityId.V1, ExerciseLevel.C)),
            ))
        }
        assertFalse(mobilePlan.exercises.isEmpty())
    }

    @Test
    fun `REQ-S8-RISK table enforces LOW B MODERATE A and HIGH A no-weight caps`() {
        data class Case(val plan: OtagoPrescriptionPlan)
        val cases = listOf(
            Case(otagoPlanFixture(
                vulnerabilityIds = listOf(VulnerabilityId.V8),
                selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.B7, VulnerabilityId.V8, ExerciseLevel.C)),
            )),
            Case(otagoPlanFixture(
                risk = SteadiRisk.MODERATE,
                vulnerabilityIds = listOf(VulnerabilityId.V7),
                selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.B5, VulnerabilityId.V7, ExerciseLevel.B)),
            )),
            Case(otagoPlanFixture(
                risk = SteadiRisk.MODERATE,
                vulnerabilityIds = listOf(VulnerabilityId.V3),
                selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.S1, VulnerabilityId.V3, weightMode = WeightMode.ANKLE_CUFF, weightMinKg = 2.0, weightMaxKg = 3.0)),
            )),
            Case(otagoPlanFixture(
                risk = SteadiRisk.HIGH,
                vulnerabilityIds = listOf(VulnerabilityId.V3),
                selectedExercises = listOf(prescribedExerciseFixture(ExerciseId.S1, VulnerabilityId.V3, weightMode = WeightMode.ANKLE_CUFF, weightMinKg = 1.0, weightMaxKg = 1.0)),
            )),
        )

        cases.forEach { case ->
            assertThrows(AssessmentSessionContractException::class.java) {
                OtagoPrescriptionContract.validate(case.plan)
            }
        }
    }

    @Test
    fun `REQ-S8-PROGRESSION requires exactly two distinct sessions and explicit user or caregiver approval`() {
        val exercise = prescribedExerciseFixture(ExerciseId.S4, VulnerabilityId.V1, ExerciseLevel.C)
        val eligible = ProgressionProposal(
            proposalId = "progression-1",
            exerciseId = ExerciseId.S4,
            fromLevel = ExerciseLevel.C,
            toLevel = ExerciseLevel.D,
            fromVariantId = "S4-C",
            toVariantId = "S4-D",
            progressionType = ProgressionType.REMOVE_SUPPORT,
            weightIncrementMinKg = null,
            weightIncrementMaxKg = null,
            status = ProgressionStatus.PENDING_APPROVAL,
            qualifyingSessionIds = listOf("session-1", "session-2"),
            approval = null,
        )
        val plan = otagoPlanFixture(
            vulnerabilityIds = listOf(VulnerabilityId.V1),
            selectedExercises = listOf(exercise),
            progressionProposals = listOf(eligible),
        )
        OtagoPrescriptionContract.validate(plan)

        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(
                progressionProposals = listOf(eligible.copy(qualifyingSessionIds = listOf("session-1", "session-1"))),
            ))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(
                progressionProposals = listOf(eligible.copy(status = ProgressionStatus.APPROVED)),
            ))
        }

        val approved = eligible.copy(
            status = ProgressionStatus.APPROVED,
            approval = ProgressionApproval(ProgressionApprovalActor.CAREGIVER_OR_RESPONSIBLE, "caregiver-1", 1_700_000_020_000L),
        )
        OtagoPrescriptionContract.validate(plan.copy(progressionProposals = listOf(approved)))
    }

    @Test
    fun `REQ-S8-PROGRESSION S1 through S3 weight progression keeps variant and adds exactly point five to one kg`() {
        val exercise = prescribedExerciseFixture(
            ExerciseId.S1,
            VulnerabilityId.V3,
            weightMode = WeightMode.FATIGUE_TARGET,
            weightMinKg = 1.0,
            weightMaxKg = 8.0,
        )
        val proposal = ProgressionProposal(
            proposalId = "weight-progression-1",
            exerciseId = ExerciseId.S1,
            fromLevel = exercise.level,
            toLevel = exercise.level,
            fromVariantId = exercise.variantId,
            toVariantId = exercise.variantId,
            progressionType = ProgressionType.INCREASE_WEIGHT,
            weightIncrementMinKg = 0.5,
            weightIncrementMaxKg = 1.0,
            status = ProgressionStatus.PENDING_APPROVAL,
            qualifyingSessionIds = listOf("session-1", "session-2"),
            approval = null,
        )
        val plan = otagoPlanFixture(
            vulnerabilityIds = listOf(VulnerabilityId.V3),
            selectedExercises = listOf(exercise),
            progressionProposals = listOf(proposal),
        )

        assertEquals(plan, OtagoPrescriptionContract.decode(OtagoPrescriptionContract.encode(plan)))
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(
                progressionProposals = listOf(proposal.copy(weightIncrementMinKg = 0.25)),
            ))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(
                progressionProposals = listOf(proposal.copy(toVariantId = "S1-next")),
            ))
        }
    }

    @Test
    fun `REQ-S8-CONTRACT unknown nested prescription field is rejected`() {
        val json = OtagoPrescriptionContract.encode(otagoPlanFixture())
        json.getJSONArray("warmups").getJSONObject(0).put("demoFallback", true)

        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.decode(json)
        }
    }

    @Test
    fun `REQ-S8-CONTRACT Mobile rejects a variant ID or level absent from the canonical Web catalog`() {
        val canonical = prescribedExerciseFixture(ExerciseId.S4, VulnerabilityId.V1, ExerciseLevel.C)
            .copy(supportRequirement = SupportRequirement.STABLE_SUPPORT)
        val plan = otagoPlanFixture(
            vulnerabilityIds = listOf(VulnerabilityId.V1),
            selectedExercises = listOf(canonical),
        )

        OtagoPrescriptionContract.validate(plan)
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(selectedExercises = listOf(canonical.copy(variantId = "S4-FAKE"))))
        }
        assertThrows(AssessmentSessionContractException::class.java) {
            OtagoPrescriptionContract.validate(plan.copy(selectedExercises = listOf(canonical.copy(level = ExerciseLevel.A, variantId = "S4-A"))))
        }
    }

    @Test
    fun `REQ-S8-RESULT typed exercise result round trips and rejects unknown fields`() {
        val result = ExerciseSessionResult(
            resultId = "exercise-result-1",
            planId = "plan-1",
            exerciseSessionId = "exercise-session-1",
            exerciseId = ExerciseId.B5,
            variantId = "B5-A",
            level = ExerciseLevel.A,
            source = ExerciseResultSource.LIVE_POSE,
            startedAt = 1_700_000_000_000L,
            completedAt = 1_700_000_010_000L,
            prescribedDosage = ExerciseDosage(null, 1, null, null, 10),
            completedDosage = ExerciseDosage(null, 1, null, null, 10),
            formAccurate = true,
            lowerBodyRecoveryWithoutGripping = true,
            supportUsed = false,
            safetyEvents = emptyList(),
            cameraVerification = CameraVerification.FULL,
        )

        assertEquals(result, ExerciseSessionResultJsonCodec.decode(ExerciseSessionResultJsonCodec.encode(result)))

        val unknown = JSONObject(ExerciseSessionResultJsonCodec.encode(result)).put("queryDemoState", true)
        assertThrows(AssessmentSessionContractException::class.java) {
            ExerciseSessionResultJsonCodec.decode(unknown.toString())
        }
        assertTrue(result.formAccurate)
    }
}
