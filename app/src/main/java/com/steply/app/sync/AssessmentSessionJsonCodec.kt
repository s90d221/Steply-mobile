package com.steply.app.sync

import com.steply.app.domain.model.*
import com.steply.app.domain.steadi.SteadiScorer
import org.json.JSONArray
import org.json.JSONObject

class AssessmentSessionContractException(message: String) : IllegalArgumentException(message)

object AssessmentSessionJsonCodec {
    fun encode(envelope: AssessmentSessionEnvelope): String = envelopeToJson(envelope).toString()

    fun canonicalResultJson(result: AcceptedAssessmentResult, schemaVersion: String): String =
        result.toJson(schemaVersion).toString()

    fun envelopeToJson(envelope: AssessmentSessionEnvelope): JSONObject {
        validate(envelope)
        return JSONObject()
            .put("schemaVersion", envelope.session.schemaVersion)
            .put("type", ASSESSMENT_SESSION_UPDATED_TYPE)
            .put("messageId", envelope.messageId)
            .put("assessmentSessionId", envelope.session.assessmentSessionId)
            .put("baseRevision", envelope.baseRevision)
            .put("revision", envelope.revision)
            .put("session", sessionToJson(envelope.session))
    }

    fun sessionToJson(session: AssessmentSession): JSONObject {
        validate(session)
        val json = JSONObject()
            .put("schemaVersion", session.schemaVersion)
            .put("assessmentSessionId", session.assessmentSessionId)
            .putNullable("connectionSessionId", session.connectionSessionId)
            .put("profileId", session.profileId)
            .put("revision", session.revision)
            .put("status", session.status.name)
            .put("screening", session.screening.toJson())
            .put("profileSnapshot", session.profileSnapshot.toJson())
            .put(
                "functionalTests",
                JSONObject()
                    .put("FOUR_STAGE_BALANCE", session.functionalTests.fourStageBalance.toJson(session.schemaVersion))
                    .put("CHAIR_STAND_30S", session.functionalTests.chairStand30s.toJson(session.schemaVersion)),
            )
            .put("steadi", session.steadi.toJson())
            .put("exercisePrescription", session.exercisePrescription.toJson(session.schemaVersion))
            .put("createdAt", session.createdAt)
            .put("updatedAt", session.updatedAt)
            .putNullable("completedAt", session.completedAt)
        if (session.schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
            json.put("operationalContext", requireNotNull(session.operationalContext).toJson())
            json.putNullable("vulnerabilityAssessment", session.vulnerabilityAssessment?.toJson())
        }
        return json
    }

    fun decode(rawJson: String): AssessmentSessionEnvelope {
        val json = try {
            JSONObject(rawJson)
        } catch (error: Throwable) {
            throw AssessmentSessionContractException("Assessment envelope is not valid JSON: ${error.message}")
        }
        return decode(json)
    }

    fun decode(json: JSONObject): AssessmentSessionEnvelope {
        json.requireOnlyKeys("schemaVersion", "type", "messageId", "assessmentSessionId", "baseRevision", "revision", "session")
        val schemaVersion = json.requireSupportedSchemaVersion()
        json.requireExactString("type", ASSESSMENT_SESSION_UPDATED_TYPE)
        val messageId = json.requireNonBlankString("messageId")
        val assessmentSessionId = json.requireNonBlankString("assessmentSessionId")
        val baseRevision = json.requireLong("baseRevision")
        val envelopeRevision = json.requireLong("revision")
        val session = decodeSession(json.requireObject("session"))
        if (schemaVersion != session.schemaVersion) contractError("Envelope and session schema versions differ")
        if (assessmentSessionId != session.assessmentSessionId) contractError("Envelope assessmentSessionId differs from session")
        if (envelopeRevision != session.revision) contractError("Envelope and session revisions differ")
        return AssessmentSessionEnvelope(messageId, baseRevision, session).also(::validate)
    }

    fun decodeSession(json: JSONObject): AssessmentSession {
        val schemaVersion = json.requireSupportedSchemaVersion()
        if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
            json.requireOnlyKeys(
                "schemaVersion", "assessmentSessionId", "connectionSessionId", "profileId", "revision", "status",
                "screening", "profileSnapshot", "operationalContext", "functionalTests", "vulnerabilityAssessment",
                "steadi", "exercisePrescription", "createdAt", "updatedAt", "completedAt",
            )
        } else {
            json.requireOnlyKeys(
                "schemaVersion", "assessmentSessionId", "connectionSessionId", "profileId", "revision", "status",
                "screening", "profileSnapshot", "functionalTests", "steadi", "exercisePrescription",
                "createdAt", "updatedAt", "completedAt",
            )
        }
        val functionalTests = json.requireObject("functionalTests")
        functionalTests.requireOnlyKeys("FOUR_STAGE_BALANCE", "CHAIR_STAND_30S")
        return AssessmentSession(
            schemaVersion = schemaVersion,
            assessmentSessionId = json.requireNonBlankString("assessmentSessionId"),
            connectionSessionId = json.requireNullableString("connectionSessionId"),
            profileId = json.requireNonBlankString("profileId"),
            revision = json.requireLong("revision"),
            status = json.requireEnum("status"),
            screening = json.requireObject("screening").toScreening(),
            profileSnapshot = json.requireObject("profileSnapshot").toProfileSnapshot(),
            operationalContext = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                json.requireObject("operationalContext").toOperationalContext()
            } else {
                null
            },
            functionalTests = AssessmentFunctionalTests(
                fourStageBalance = functionalTests.requireObject("FOUR_STAGE_BALANCE")
                    .toTestSlot(AssessmentType.FOUR_STAGE_BALANCE, schemaVersion),
                chairStand30s = functionalTests.requireObject("CHAIR_STAND_30S")
                    .toTestSlot(AssessmentType.CHAIR_STAND_30S, schemaVersion),
            ),
            vulnerabilityAssessment = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                json.requireNullableObject("vulnerabilityAssessment")?.toVulnerabilityAssessment()
            } else null,
            steadi = json.requireObject("steadi").toSteadi(),
            exercisePrescription = json.requireObject("exercisePrescription").toPrescription(schemaVersion),
            createdAt = json.requireLong("createdAt"),
            updatedAt = json.requireLong("updatedAt"),
            completedAt = json.requireNullableLong("completedAt"),
        )
    }

    fun validate(envelope: AssessmentSessionEnvelope) {
        if (envelope.messageId.isBlank()) contractError("messageId must not be blank")
        if (envelope.baseRevision < 0L || envelope.baseRevision > envelope.revision) {
            contractError("baseRevision must be within 0..revision")
        }
        validate(envelope.session)
    }

    fun validate(session: AssessmentSession) {
        if (session.schemaVersion != ASSESSMENT_SESSION_SCHEMA_VERSION &&
            session.schemaVersion != LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION
        ) contractError("Unsupported AssessmentSession schemaVersion")
        if (session.assessmentSessionId.isBlank()) contractError("assessmentSessionId must not be blank")
        if (session.connectionSessionId?.isBlank() == true) contractError("connectionSessionId must be null or non-blank")
        if (session.profileId.isBlank()) contractError("profileId must not be blank")
        if (session.revision < 0L) contractError("revision must be non-negative")
        if (session.createdAt < 0L || session.updatedAt < session.createdAt) contractError("timestamps are invalid")
        session.profileSnapshot.birthYear?.let { if (it < 1900) contractError("birthYear must be at least 1900") }
        session.profileSnapshot.ageYears?.let { if (it !in 0..130) contractError("ageYears must be within 0..130") }
        if (session.schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
            val context = session.operationalContext ?: contractError("v2 session requires operationalContext")
            if (context.operationalConfigVersion != null && context.operationalConfigVersion != STAGE2_OPERATIONAL_CONFIG_VERSION) {
                contractError("operationalConfigVersion must be $STAGE2_OPERATIONAL_CONFIG_VERSION")
            }
            context.supportRoiNormalized?.let(::validateRoi)
            session.vulnerabilityAssessment?.let(::validateVulnerability)
        }
        validateSlot(session.functionalTests.fourStageBalance, AssessmentType.FOUR_STAGE_BALANCE, session.schemaVersion)
        validateSlot(session.functionalTests.chairStand30s, AssessmentType.CHAIR_STAND_30S, session.schemaVersion)
        if (session.schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) validateSupportRoiConsistency(session)
        validateSteadi(session.steadi)
        if (session.steadi.status == SteadiStatus.SCORED) {
            val expected = SteadiScorer.score(session)
            if (expected.status != SteadiStatus.SCORED ||
                expected.risk != session.steadi.risk ||
                expected.strengthProblem != session.steadi.strengthProblem ||
                expected.balanceProblem != session.steadi.balanceProblem ||
                expected.step1AtRisk != session.steadi.step1AtRisk ||
                expected.step2Problem != session.steadi.step2Problem ||
                session.steadi.ruleVersion != STEADI_RULE_VERSION
            ) {
                contractError("STEADI score does not match the deterministic Stage 1 rules")
            }
        }

        val prescription = session.exercisePrescription
        if (prescription.status == PrescriptionStatus.NOT_GENERATED &&
            (prescription.plan != null || prescription.sessionResults.isNotEmpty())
        ) {
            contractError("NOT_GENERATED prescription must have a null plan and no session results")
        }
        if (prescription.status != PrescriptionStatus.NOT_GENERATED) {
            val plan = prescription.plan ?: contractError("Generated prescription requires a plan")
            OtagoPrescriptionContract.validate(plan)
            validatePrescriptionConsistency(session, prescription, plan)
        }

        if (session.status == AssessmentSessionStatus.COMPLETED) {
            if (session.completedAt == null) contractError("COMPLETED session requires completedAt")
            if (!session.hasScoredAggregate()) contractError("COMPLETED session requires completed slots and SCORED STEADI")
        } else {
            if (session.status == AssessmentSessionStatus.IN_PROGRESS && session.completedAt != null) {
                contractError("IN_PROGRESS session must not have completedAt")
            }
            if (session.steadi.status == SteadiStatus.SCORED) contractError("STEADI cannot be SCORED before session completion")
            if (prescription.status != PrescriptionStatus.NOT_GENERATED) contractError("Prescription cannot exist before completion")
        }
    }

    private fun validateSlot(slot: AssessmentTestSlot, expectedType: AssessmentType, schemaVersion: String) {
        if (slot.status == AssessmentSlotStatus.COMPLETED &&
            (slot.acceptedAttemptId == null || slot.acceptedResult == null)
        ) {
            contractError("COMPLETED $expectedType slot requires an accepted result")
        }
        if (slot.acceptedAttemptId == null && slot.acceptedResult != null) contractError("Accepted result requires acceptedAttemptId")
        slot.acceptedResult?.let { result ->
            if (result.assessmentType != expectedType) contractError("Accepted result type does not match $expectedType")
            if (result.attemptId != slot.acceptedAttemptId) contractError("Accepted result attemptId does not match slot")
            if (result.resultId.isBlank() || result.analysisSessionId.isBlank() || result.source != "LIVE_POSE") {
                contractError("Accepted result identity/source is invalid")
            }
            if (result.status != AssessmentResultStatus.VALID) contractError("Accepted result must be VALID")
            validateResult(result, expectedType, schemaVersion)
        }
        val attemptIds = mutableSetOf<String>()
        slot.attempts.forEach { attempt ->
            if (!attemptIds.add(attempt.attemptId)) contractError("Duplicate attemptId: ${attempt.attemptId}")
            if (attempt.attemptId.isBlank() || attempt.analysisSessionId.isBlank()) contractError("Attempt IDs must not be blank")
            if (attempt.startedAt < 0L || (attempt.completedAt != null && attempt.completedAt < attempt.startedAt)) {
                contractError("Attempt timestamps are invalid")
            }
            attempt.result?.let { result ->
                if (result.attemptId != attempt.attemptId || result.analysisSessionId != attempt.analysisSessionId) {
                    contractError("Attempt result identity does not match its attempt")
                }
                validateResult(result, expectedType, schemaVersion)
                if (result.status == AssessmentResultStatus.INVALID && result.quality?.excludeFromTrends != true) {
                    contractError("INVALID result must be excluded from trends")
                }
                if (attempt.resultHash != result.resultHash) contractError("Attempt resultHash does not match result")
            }
        }
        slot.acceptedResult?.let { result ->
            val acceptedAttempt = slot.attempts.firstOrNull { it.attemptId == result.attemptId }
                ?: contractError("Accepted attempt is missing from attempts")
            if (acceptedAttempt.analysisSessionId != result.analysisSessionId) {
                contractError("Accepted result analysisSessionId does not match attempt")
            }
        }
    }

    private fun validateResult(result: AcceptedAssessmentResult, expectedType: AssessmentType, schemaVersion: String) {
        if (result.assessmentType != expectedType) contractError("Result type does not match $expectedType")
        if (result.resultId.isBlank() || result.attemptId.isBlank() || result.analysisSessionId.isBlank()) {
            contractError("Result IDs must not be blank")
        }
        if (result.source != "LIVE_POSE" || result.completedAt < 0L) contractError("Result source/timestamp is invalid")
        if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) {
            when (expectedType) {
                AssessmentType.CHAIR_STAND_30S -> if (result.completedRepetitions == null || result.armUseConfirmed == null) {
                    contractError("Legacy chair result is incomplete")
                }
                AssessmentType.FOUR_STAGE_BALANCE -> if (result.tandemHoldSeconds == null) {
                    contractError("Legacy balance result is incomplete")
                }
            }
            return
        }
        if (result.legacyReadOnly) {
            if (result.resultSchemaVersion != LEGACY_RESULT_SCHEMA_VERSION || result.status != AssessmentResultStatus.VALID ||
                result.resultHash?.matches(Regex("^[0-9a-f]{64}$")) != true
            ) contractError("Invalid legacy read-only result")
            when (expectedType) {
                AssessmentType.CHAIR_STAND_30S -> if (result.completedRepetitions == null || result.armUseConfirmed == null) contractError("Legacy chair result is incomplete")
                AssessmentType.FOUR_STAGE_BALANCE -> if (result.tandemHoldSeconds == null) contractError("Legacy balance result is incomplete")
            }
            return
        }
        if (result.operationalConfigVersion != STAGE2_OPERATIONAL_CONFIG_VERSION) {
            contractError("Result operationalConfigVersion must be $STAGE2_OPERATIONAL_CONFIG_VERSION")
        }
        if (result.resultSchemaVersion != STAGE2_RESULT_SCHEMA_VERSION) contractError("Invalid resultSchemaVersion")
        if (result.resultHash?.matches(Regex("^[0-9a-f]{64}$")) != true) contractError("Invalid resultHash")
        validateCalibration(result.calibration ?: contractError("v2 result requires calibration"), expectedType)
        validateQuality(result.quality ?: contractError("v2 result requires quality"), result.status)
        result.vulnerabilityAssessment?.let(::validateVulnerability)
        when (expectedType) {
            AssessmentType.CHAIR_STAND_30S -> {
                if (result.balance != null) contractError("Chair result cannot contain balance measurements")
                val chair = result.chairStand ?: contractError("Chair result requires chairStand measurements")
                if (chair.observedRepetitions < 0 || chair.completedRepetitions < 0 || chair.cdcScoredRepetitions < 0) {
                    contractError("Chair repetition values must be non-negative")
                }
                if (chair.cdcScoredRepetitions > chair.completedRepetitions) contractError("CDC score cannot exceed completed repetitions")
                if (chair.armUse.occurrenceCount < 0 || chair.armUse.occurrenceCount > 2) contractError("arm occurrenceCount is invalid")
                if (chair.finalRepetitionCredit !in 0..1) contractError("finalRepetitionCredit must be 0 or 1")
                when (chair.armUse.outcome) {
                    ArmUseOutcome.NOT_DETECTED -> {
                        if (chair.armUse.occurrenceCount != 0 || chair.armUse.restartUsed) {
                            contractError("NOT_DETECTED arm use requires zero occurrences and no restart")
                        }
                        requireNoV6(result)
                    }
                    ArmUseOutcome.RESTART_REQUIRED -> {
                        if (!chair.armUse.restartUsed || chair.armUse.occurrenceCount != 1 ||
                            result.status != AssessmentResultStatus.INVALID || chair.cdcScoredRepetitions != 0
                        ) {
                            contractError("First arm-use occurrence must consume one restart, invalidate the attempt, and carry no CDC score")
                        }
                        requireNoV6(result)
                    }
                    ArmUseOutcome.DISQUALIFIED -> {
                        if (!chair.armUse.restartUsed || chair.armUse.occurrenceCount != 2 ||
                            chair.cdcScoredRepetitions != 0 || result.status != AssessmentResultStatus.VALID
                        ) {
                            contractError("Second arm-use occurrence requires a valid DISQUALIFIED result, restartUsed, and CDC zero")
                        }
                        requireV6Evidence(result)
                    }
                    ArmUseOutcome.NOT_MEASURABLE -> Unit
                }
            }
            AssessmentType.FOUR_STAGE_BALANCE -> {
                if (result.chairStand != null) contractError("Balance result cannot contain chair measurements")
                val stages = result.balance?.stages ?: contractError("Balance result requires stages")
                if (stages.map { it.stage } != BalanceStage.entries) contractError("Balance stages must be in protocol order")
                stages.forEach { stage ->
                    if (!stage.holdSeconds.isFinite() || stage.holdSeconds !in 0.0..10.0) contractError("holdSeconds must be within 0..10")
                    if (stage.onsetLatencyMs != null && stage.onsetLatencyMs < 0L) contractError("onsetLatencyMs must be non-negative")
                    if (stage.failureReason?.isBlank() == true) contractError("failureReason must be null or non-blank")
                    stage.sway?.let(::validateSway)
                }
                if (result.status == AssessmentResultStatus.VALID && stages.none { it.stage == BalanceStage.TANDEM }) {
                    contractError("Valid balance result requires TANDEM")
                }
            }
        }
    }

    private fun validateCalibration(value: AssessmentCalibration, expectedType: AssessmentType) {
        if (value.sampledDurationMs < 3_000L) contractError("Calibration requires at least 3000ms")
        listOf(value.lFootM, value.wShoulderM).forEach {
            if (!it.isFinite() || it <= 0.0) contractError("Calibration references must be positive finite values")
        }
        if (!value.hStandM.isFinite()) contractError("hStandM is invalid")
        if (expectedType == AssessmentType.CHAIR_STAND_30S &&
            (value.hSitM == null || !value.hSitM.isFinite() || value.dFoldM == null || !value.dFoldM.isFinite())
        ) contractError("Chair calibration requires hSitM and dFoldM")
        value.dFoldM?.let { if (!it.isFinite() || it <= 0.0) contractError("dFoldM is invalid") }
        value.supportRoiNormalized?.let(::validateRoi)
    }

    private fun validateQuality(value: AssessmentQualitySummary, status: AssessmentResultStatus) {
        if (value.gates.map { it.gate } != QualityGateId.entries) {
            contractError("quality.gates must contain G1..G5 exactly once in canonical order")
        }
        if (!value.g3ViolationRatio.isFinite() || value.g3ViolationRatio !in 0.0..1.0) contractError("g3ViolationRatio is invalid")
        value.gates.forEach {
            if (it.violationFrameCount < 0 || it.violationDurationMs < 0L || !it.violationRatio.isFinite() || it.violationRatio !in 0.0..1.0) {
                contractError("Quality gate observation is invalid")
            }
        }
        if (value.invalidReasons.any { it.isBlank() } || value.invalidReasons.distinct().size != value.invalidReasons.size) {
            contractError("invalidReasons must be unique non-blank values")
        }
        if (status == AssessmentResultStatus.VALID && value.excludeFromTrends) contractError("VALID result cannot be excluded from trends")
        if (status != AssessmentResultStatus.VALID && !value.excludeFromTrends) contractError("Invalid result must be excluded from trends")
        if (value.g3ViolationRatio > G3_INVALIDATION_RATIO) {
            if (status != AssessmentResultStatus.INVALID || !value.excludeFromTrends) {
                contractError("G3 violation ratio above 20% requires INVALID status and trend exclusion")
            }
            if (G3_INVALID_REASON !in value.invalidReasons) {
                contractError("G3 violation ratio above 20% requires $G3_INVALID_REASON")
            }
        } else if (G3_INVALID_REASON in value.invalidReasons) {
            contractError("$G3_INVALID_REASON requires G3 violation ratio above 20%")
        }
    }

    private fun validateSupportRoiConsistency(session: AssessmentSession) {
        val expected = requireNotNull(session.operationalContext).supportRoiNormalized
        val results = listOf(session.functionalTests.fourStageBalance, session.functionalTests.chairStand30s)
            .flatMap { slot -> listOfNotNull(slot.acceptedResult) + slot.attempts.mapNotNull { it.result } }
            .distinctBy { it.resultId }
            .filterNot { it.legacyReadOnly }
        results.forEach { result ->
            val calibrationRoi = result.calibration?.supportRoiNormalized
            when (result.assessmentType) {
                AssessmentType.FOUR_STAGE_BALANCE -> if (calibrationRoi != expected) {
                    contractError("Balance calibration support ROI must match operationalContext")
                }
                AssessmentType.CHAIR_STAND_30S -> if (calibrationRoi != null) {
                    contractError("Chair Stand calibration must not contain a support ROI")
                }
            }
        }
    }

    private fun validatePrescriptionConsistency(
        session: AssessmentSession,
        prescription: AssessmentPrescription,
        plan: OtagoPrescriptionPlan,
    ) {
        if (plan.userId != session.profileId) contractError("Prescription userId must match profileId")
        if (plan.riskLevel != session.steadi.risk) contractError("Prescription riskLevel must match STEADI risk")
        if (plan.vulnerabilityIds.toSet() != session.vulnerabilityAssessment?.activeIds.orEmpty().toSet()) {
            contractError("Prescription vulnerabilityIds must match the assessment")
        }
        if (session.assessmentSessionId !in plan.sourceAssessmentIds) contractError("Prescription must cite assessmentSessionId")
        val acceptedResultIds = listOfNotNull(
            session.functionalTests.fourStageBalance.acceptedResult?.resultId,
            session.functionalTests.chairStand30s.acceptedResult?.resultId,
        )
        if (!plan.sourceResultIds.containsAll(acceptedResultIds)) contractError("Prescription must cite accepted result IDs")
        when (prescription.status) {
            PrescriptionStatus.NOT_GENERATED -> contractError("Generated plan cannot have NOT_GENERATED status")
            PrescriptionStatus.BLOCKED -> if (plan.status != OtagoPlanStatus.BLOCKED) contractError("BLOCKED prescription requires BLOCKED plan")
            PrescriptionStatus.ACTIVE -> if (plan.status != OtagoPlanStatus.ACTIVE) contractError("ACTIVE prescription requires ACTIVE plan")
            PrescriptionStatus.PENDING_PROFESSIONAL_REVIEW -> if (plan.status != OtagoPlanStatus.PENDING_PROFESSIONAL_REVIEW) {
                contractError("Pending prescription requires a pending plan")
            }
        }
        val results = prescription.sessionResults
        if (results.map { it.resultId }.distinct().size != results.size ||
            results.map { it.exerciseSessionId }.distinct().size != results.size
        ) contractError("Exercise session result IDs must be unique")
        if (plan.status != OtagoPlanStatus.ACTIVE && results.isNotEmpty()) contractError("Exercise results require an ACTIVE plan")
        val prescribed = (plan.warmups + plan.selectedExercises).associateBy { it.exerciseId }
        results.forEach { result ->
            ExerciseSessionResultJsonCodec.validate(result)
            if (result.planId != plan.planId) contractError("Exercise result planId differs from prescription")
            if (result.exerciseId == ExerciseId.WALK) {
                if (plan.walkingPlan == null) contractError("WALK result requires walkingPlan")
            } else {
                val item = prescribed[result.exerciseId] ?: contractError("Exercise result is not in the prescription")
                if (result.variantId != item.variantId || result.level != item.level || result.cameraVerification != item.cameraVerification) {
                    contractError("Exercise result variant differs from prescription")
                }
            }
        }
        plan.progressionProposals.forEach { proposal ->
            val qualifying = proposal.qualifyingSessionIds.map { sessionId ->
                results.firstOrNull { it.exerciseSessionId == sessionId }
                    ?: contractError("Progression qualifying session is missing")
            }
            if (qualifying.any { it.exerciseId != proposal.exerciseId || !it.formAccurate || it.safetyEvents.isNotEmpty() }) {
                contractError("Progression requires two accurate, safe results for the same exercise")
            }
            when (proposal.exerciseId.expectedCategory()) {
                ExerciseCategory.STRENGTH -> if (qualifying.any {
                        (it.completedDosage.repetitions ?: it.completedDosage.repetitionsPerSide ?: 0) < 10 ||
                            (it.completedDosage.sets ?: 0) < 2
                    }
                ) contractError("Strength progression requires 10 repetitions times 2 sets in both qualifying sessions")
                ExerciseCategory.BALANCE -> if (qualifying.any { it.lowerBodyRecoveryWithoutGripping != true }) {
                    contractError("Balance progression requires lower-body recovery without gripping")
                }
                ExerciseCategory.WARMUP, ExerciseCategory.WALKING -> contractError("Warm-up and walking results cannot generate progression")
            }
        }
    }

    private fun requireV6Evidence(result: AcceptedAssessmentResult) {
        val vulnerability = result.vulnerabilityAssessment
            ?: contractError("Disqualified arm use requires result-level V6 evidence")
        if (VulnerabilityId.V6 !in vulnerability.activeIds) {
            contractError("Disqualified arm use requires result-level V6")
        }
        val evidence = vulnerability.evidence.firstOrNull { it.vulnerabilityId == VulnerabilityId.V6 }
            ?: contractError("Disqualified arm use requires result-level V6 evidence")
        if (evidence.sourceResultId != result.resultId) {
            contractError("V6 evidence must reference the disqualified Chair Stand result")
        }
    }

    private fun requireNoV6(result: AcceptedAssessmentResult) {
        if (VulnerabilityId.V6 in result.vulnerabilityAssessment?.activeIds.orEmpty()) {
            contractError("V6 is reserved for second-occurrence arm-use disqualification")
        }
    }

    private fun validateSway(value: BalanceSwayMetrics) {
        listOf(value.mlRmsM, value.apRmsM).forEach { if (it != null && (!it.isFinite() || it < 0.0)) contractError("RMS sway is invalid") }
        listOf(value.initialRmsM, value.staticRmsM, value.initialToStaticRatio, value.mlToApRatio).forEach {
            if (it != null && (!it.isFinite() || it < 0.0)) contractError("Sway ratio/window value is invalid")
        }
    }

    private fun validateRoi(value: NormalizedRoi) {
        val values = listOf(value.x, value.y, value.width, value.height)
        if (values.any { !it.isFinite() || it !in 0.0..1.0 } || value.width <= 0.0 || value.height <= 0.0 ||
            value.x + value.width > 1.0 || value.y + value.height > 1.0
        ) {
            contractError("supportRoiNormalized is invalid")
        }
    }

    private fun validateVulnerability(value: VulnerabilityAssessment) {
        if (value.ruleVersion.isBlank() || value.activeIds.distinct().size != value.activeIds.size) {
            contractError("Vulnerability assessment is invalid")
        }
        if (value.evidence.map { it.vulnerabilityId }.toSet() != value.activeIds.toSet()) {
            contractError("Vulnerability evidence must cover activeIds")
        }
        value.evidence.forEach { runCatching { JSONObject(it.measurementsJson) }.getOrElse { contractError("Vulnerability measurements must be an object") } }
    }

    private fun validateSteadi(steadi: SteadiScore) {
        if (steadi.ruleVersion.isBlank()) contractError("STEADI ruleVersion must not be blank")
        if (steadi.reasonCodes.any { it.isBlank() } || steadi.reasonCodes.distinct().size != steadi.reasonCodes.size) {
            contractError("STEADI reasonCodes must be unique non-blank values")
        }
        when (steadi.status) {
            SteadiStatus.NOT_SCORABLE -> {
                if (steadi.risk != SteadiRisk.NOT_SCORABLE || steadi.strengthProblem != null || steadi.balanceProblem != null) {
                    contractError("NOT_SCORABLE STEADI fields are inconsistent")
                }
                if (steadi.reasonCodes.isEmpty()) contractError("NOT_SCORABLE requires a reason code")
            }
            SteadiStatus.SCORED -> {
                if (steadi.risk == SteadiRisk.NOT_SCORABLE || steadi.strengthProblem == null ||
                    steadi.balanceProblem == null || steadi.step1AtRisk == null || steadi.step2Problem == null
                ) {
                    contractError("SCORED STEADI fields are incomplete")
                }
            }
        }
    }

    private fun AssessmentScreening.toJson() = JSONObject()
        .put("status", status.name)
        .put(
            "responses",
            JSONObject()
                .putNullable("fallenPastYear", fallenPastYear)
                .putNullable("feelsUnsteady", feelsUnsteady)
                .putNullable("worriedAboutFalling", worriedAboutFalling),
        )
        .put(
            "fallHistory",
            JSONObject()
                .putNullable("count", fallCount?.name)
                .putNullable("injuriousFall", injuriousFall),
        )

    private fun AssessmentProfileSnapshot.toJson() = JSONObject()
        .putNullable("birthYear", birthYear)
        .putNullable("ageYears", ageYears)
        .putNullable("sex", sex?.name)

    private fun AssessmentOperationalContext.toJson() = JSONObject()
        .putNullable("operationalConfigVersion", operationalConfigVersion)
        .putNullable("supportRoiNormalized", supportRoiNormalized?.toJson())

    private fun NormalizedRoi.toJson() = JSONObject()
        .put("x", x)
        .put("y", y)
        .put("width", width)
        .put("height", height)

    private fun AssessmentTestSlot.toJson(schemaVersion: String) = JSONObject()
        .put("status", status.name)
        .putNullable("acceptedAttemptId", acceptedAttemptId)
        .putNullable("acceptedResult", acceptedResult?.toJson(schemaVersion))
        .put("attempts", JSONArray().also { array -> attempts.forEach { array.put(it.toJson(schemaVersion)) } })

    private fun AssessmentAttemptSummary.toJson(schemaVersion: String) = JSONObject()
        .put("attemptId", attemptId)
        .put("analysisSessionId", analysisSessionId)
        .put("status", status.name)
        .put("startedAt", startedAt)
        .putNullable("completedAt", completedAt)
        .putNullable("supersedesAttemptId", supersedesAttemptId)
        .also {
            if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                it.putNullable("resultHash", resultHash)
                it.putNullable("result", result?.toJson(schemaVersion))
            }
        }

    private fun AcceptedAssessmentResult.toJson(schemaVersion: String): JSONObject {
        val json = JSONObject()
            .put("resultId", resultId)
        .put("attemptId", attemptId)
        .put("analysisSessionId", analysisSessionId)
        .put("assessmentType", assessmentType.name)
        .put("status", status.name)
        .put("source", source)
        .put("completedAt", completedAt)
        if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) {
            return json
                .putNullable("completedRepetitions", completedRepetitions)
                .putNullable("armUseConfirmed", armUseConfirmed)
                .putNullable("tandemHoldSeconds", tandemHoldSeconds)
                .putNullable("sideBySideHoldSeconds", sideBySideHoldSeconds)
                .putNullable("semiTandemHoldSeconds", semiTandemHoldSeconds)
                .putNullable("oneLegHoldSeconds", oneLegHoldSeconds)
        }
        if (legacyReadOnly) {
            return json
                .put("resultSchemaVersion", LEGACY_RESULT_SCHEMA_VERSION)
                .put("resultHash", resultHash)
                .put("legacyReadOnly", true)
                .putNullable("completedRepetitions", completedRepetitions)
                .putNullable("armUseConfirmed", armUseConfirmed)
                .putNullable("tandemHoldSeconds", tandemHoldSeconds)
        }
        json
            .put("resultSchemaVersion", resultSchemaVersion)
            .put("resultHash", resultHash)
            .put("operationalConfigVersion", operationalConfigVersion)
            .put("calibration", calibration?.toJson())
            .put("quality", quality?.toJson())
            .putNullable("vulnerabilityAssessment", vulnerabilityAssessment?.toJson())
        chairStand?.let { json.put("chairStand", it.toJson()) }
        balance?.let { json.put("balance", it.toJson()) }
        return json
    }

    private fun AssessmentCalibration.toJson() = JSONObject()
        .put("sampledDurationMs", sampledDurationMs)
        .put("lFootM", lFootM)
        .put("hStandM", hStandM)
        .putNullable("hSitM", hSitM)
        .put("wShoulderM", wShoulderM)
        .putNullable("dFoldM", dFoldM)
        .putNullable("supportRoiNormalized", supportRoiNormalized?.toJson())

    private fun AssessmentQualitySummary.toJson() = JSONObject()
        .put("gates", JSONArray().also { array -> gates.forEach { array.put(it.toJson()) } })
        .put("g3ViolationRatio", g3ViolationRatio)
        .put("invalidReasons", JSONArray(invalidReasons))
        .put("excludeFromTrends", excludeFromTrends)

    private fun QualityGateObservation.toJson() = JSONObject()
        .put("gate", gate.name)
        .put("violationFrameCount", violationFrameCount)
        .put("violationDurationMs", violationDurationMs)
        .put("violationRatio", violationRatio)

    private fun ChairStandMeasurements.toJson() = JSONObject()
        .put("observedRepetitions", observedRepetitions)
        .put("completedRepetitions", completedRepetitions)
        .put("cdcScoredRepetitions", cdcScoredRepetitions)
        .put("finalRepetitionCredit", finalRepetitionCredit)
        .put("finalState", finalState.name)
        .put("armUse", armUse.toJson())

    private fun ChairStandArmUse.toJson() = JSONObject()
        .put("occurrenceCount", occurrenceCount)
        .put("restartUsed", restartUsed)
        .put("outcome", outcome.name)

    private fun BalanceMeasurements.toJson() = JSONObject()
        .put("stages", JSONArray().also { array -> stages.forEach { array.put(it.toJson()) } })

    private fun BalanceStageResult.toJson() = JSONObject()
        .put("stage", stage.name)
        .putNullable("onsetLatencyMs", onsetLatencyMs)
        .put("holdSeconds", holdSeconds)
        .put("status", status.name)
        .putNullable("failureCode", failureCode?.name)
        .putNullable("failureReason", failureReason)
        .putNullable("sway", sway?.toJson())

    private fun BalanceSwayMetrics.toJson() = JSONObject()
        .putNullable("mlRmsM", mlRmsM)
        .putNullable("apRmsM", apRmsM)
        .putNullable("initialRmsM", initialRmsM)
        .putNullable("staticRmsM", staticRmsM)
        .putNullable("initialToStaticRatio", initialToStaticRatio)
        .putNullable("mlToApRatio", mlToApRatio)

    private fun VulnerabilityAssessment.toJson() = JSONObject()
        .put("activeIds", JSONArray(activeIds.map { it.name }))
        .put("evidence", JSONArray().also { array -> evidence.forEach { array.put(it.toJson()) } })
        .put("ruleVersion", ruleVersion)

    private fun VulnerabilityEvidence.toJson() = JSONObject()
        .put("vulnerabilityId", vulnerabilityId.name)
        .putNullable("sourceResultId", sourceResultId)
        .put("measurements", JSONObject(measurementsJson))

    private fun SteadiScore.toJson() = JSONObject()
        .put("status", status.name)
        .put("riskLevel", risk.name)
        .putNullable("strengthProblem", strengthProblem)
        .putNullable("balanceProblem", balanceProblem)
        .putNullable("step1AtRisk", step1AtRisk)
        .putNullable("step2Problem", step2Problem)
        .put("reasonCodes", JSONArray(reasonCodes))
        .put("ruleVersion", ruleVersion)

    private fun AssessmentPrescription.toJson(schemaVersion: String) = JSONObject()
        .put("status", status.name)
        .putNullable("plan", plan?.let(OtagoPrescriptionContract::encode))
        .also { json ->
            if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                json.put("sessionResults", JSONArray().also { array -> sessionResults.forEach { array.put(ExerciseSessionResultJsonCodec.encodeObject(it)) } })
            }
        }

    private fun JSONObject.toScreening(): AssessmentScreening {
        requireOnlyKeys("status", "responses", "fallHistory")
        val responses = requireObject("responses")
        val fallHistory = requireObject("fallHistory")
        responses.requireOnlyKeys("fallenPastYear", "feelsUnsteady", "worriedAboutFalling")
        fallHistory.requireOnlyKeys("count", "injuriousFall")
        return AssessmentScreening(
            status = requireEnum("status"),
            fallenPastYear = responses.requireNullableBoolean("fallenPastYear"),
            feelsUnsteady = responses.requireNullableBoolean("feelsUnsteady"),
            worriedAboutFalling = responses.requireNullableBoolean("worriedAboutFalling"),
            fallCount = fallHistory.requireNullableEnum<AssessmentFallCount>("count"),
            injuriousFall = fallHistory.requireNullableBoolean("injuriousFall"),
        )
    }

    private fun JSONObject.toProfileSnapshot(): AssessmentProfileSnapshot {
        requireOnlyKeys("birthYear", "ageYears", "sex")
        return AssessmentProfileSnapshot(
            birthYear = requireNullableInt("birthYear"),
            ageYears = requireNullableInt("ageYears"),
            sex = requireNullableEnum<AssessmentSex>("sex"),
        )
    }

    private fun JSONObject.toOperationalContext(): AssessmentOperationalContext {
        requireOnlyKeys("operationalConfigVersion", "supportRoiNormalized")
        return AssessmentOperationalContext(
            operationalConfigVersion = requireNullableString("operationalConfigVersion"),
            supportRoiNormalized = requireNullableObject("supportRoiNormalized")?.toNormalizedRoi(),
        )
    }

    private fun JSONObject.toNormalizedRoi(): NormalizedRoi {
        requireOnlyKeys("x", "y", "width", "height")
        return NormalizedRoi(requireDouble("x"), requireDouble("y"), requireDouble("width"), requireDouble("height"))
    }

    private fun JSONObject.toTestSlot(expectedType: AssessmentType, schemaVersion: String): AssessmentTestSlot {
        requireOnlyKeys("status", "acceptedAttemptId", "acceptedResult", "attempts")
        val attemptsJson = requireArray("attempts")
        val attempts = buildList {
            for (index in 0 until attemptsJson.length()) {
                add(attemptsJson.requireObject(index).toAttempt(expectedType, schemaVersion))
            }
        }
        return AssessmentTestSlot(
            status = requireEnum("status"),
            acceptedAttemptId = requireNullableString("acceptedAttemptId"),
            acceptedResult = requireNullableObject("acceptedResult")?.toAssessmentResult(expectedType, schemaVersion),
            attempts = attempts,
        )
    }

    private fun JSONObject.toAttempt(expectedType: AssessmentType, schemaVersion: String): AssessmentAttemptSummary {
        if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
            requireOnlyKeys("attemptId", "analysisSessionId", "status", "startedAt", "completedAt", "supersedesAttemptId", "resultHash", "result")
        } else {
            requireOnlyKeys("attemptId", "analysisSessionId", "status", "startedAt", "completedAt", "supersedesAttemptId")
        }
        return AssessmentAttemptSummary(
            attemptId = requireNonBlankString("attemptId"),
            analysisSessionId = requireNonBlankString("analysisSessionId"),
            status = requireEnum("status"),
            startedAt = requireLong("startedAt"),
            completedAt = requireNullableLong("completedAt"),
            supersedesAttemptId = requireNullableString("supersedesAttemptId"),
            resultHash = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireNullableString("resultHash") else null,
            result = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                requireNullableObject("result")?.toAssessmentResult(expectedType, schemaVersion)
            } else {
                null
            },
        )
    }

    private fun JSONObject.toAssessmentResult(expectedType: AssessmentType, schemaVersion: String): AcceptedAssessmentResult {
        val legacyReadOnly = schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION &&
            optString("resultSchemaVersion") == LEGACY_RESULT_SCHEMA_VERSION
        if (legacyReadOnly) {
            requireOnlyKeys(
                "resultSchemaVersion", "resultId", "resultHash", "attemptId", "analysisSessionId", "assessmentType",
                "status", "source", "completedAt", "legacyReadOnly", "completedRepetitions", "armUseConfirmed",
                "tandemHoldSeconds",
            )
            requireExactString("resultSchemaVersion", LEGACY_RESULT_SCHEMA_VERSION)
            if (!requireBoolean("legacyReadOnly")) contractError("legacyReadOnly must be true")
            val type: AssessmentType = requireEnum("assessmentType")
            if (type != expectedType) contractError("acceptedResult assessmentType does not match slot")
            return AcceptedAssessmentResult(
                resultSchemaVersion = LEGACY_RESULT_SCHEMA_VERSION,
                resultId = requireNonBlankString("resultId"),
                resultHash = requireNonBlankString("resultHash"),
                attemptId = requireNonBlankString("attemptId"),
                analysisSessionId = requireNonBlankString("analysisSessionId"),
                assessmentType = type,
                status = requireEnum("status"),
                source = requireNonBlankString("source"),
                completedAt = requireLong("completedAt"),
                operationalConfigVersion = null,
                calibration = null,
                quality = null,
                vulnerabilityAssessment = null,
                legacyReadOnly = true,
                legacyCompletedRepetitions = optionalInt("completedRepetitions"),
                legacyArmUseConfirmed = optionalBoolean("armUseConfirmed"),
                legacyTandemHoldSeconds = optionalDouble("tandemHoldSeconds"),
            )
        }
        if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
            val typeKey = if (expectedType == AssessmentType.CHAIR_STAND_30S) "chairStand" else "balance"
            requireOnlyKeys(
                "resultId", "attemptId", "analysisSessionId", "assessmentType", "status", "source", "completedAt",
                "resultSchemaVersion", "resultHash", "operationalConfigVersion", "calibration", "quality",
                "vulnerabilityAssessment", typeKey,
            )
        } else {
            requireOnlyKeys(
                "resultId", "attemptId", "analysisSessionId", "assessmentType", "status", "source", "completedAt",
                "completedRepetitions", "armUseConfirmed", "tandemHoldSeconds", "sideBySideHoldSeconds",
                "semiTandemHoldSeconds", "oneLegHoldSeconds",
            )
        }
        val type: AssessmentType = requireEnum("assessmentType")
        if (type != expectedType) contractError("acceptedResult assessmentType does not match slot")
        return AcceptedAssessmentResult(
            resultSchemaVersion = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireNonBlankString("resultSchemaVersion") else null,
            resultId = requireNonBlankString("resultId"),
            resultHash = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireNonBlankString("resultHash") else null,
            attemptId = requireNonBlankString("attemptId"),
            analysisSessionId = requireNonBlankString("analysisSessionId"),
            assessmentType = type,
            status = requireEnum("status"),
            source = requireNonBlankString("source"),
            completedAt = requireLong("completedAt"),
            operationalConfigVersion = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireNonBlankString("operationalConfigVersion") else null,
            calibration = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireObject("calibration").toCalibration() else null,
            quality = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireObject("quality").toQuality() else null,
            vulnerabilityAssessment = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) {
                requireNullableObject("vulnerabilityAssessment")?.toVulnerabilityAssessment()
            } else null,
            chairStand = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION && expectedType == AssessmentType.CHAIR_STAND_30S) {
                requireObject("chairStand").toChairStand()
            } else null,
            balance = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION && expectedType == AssessmentType.FOUR_STAGE_BALANCE) {
                requireObject("balance").toBalance()
            } else null,
            legacyCompletedRepetitions = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalInt("completedRepetitions") else null,
            legacyArmUseConfirmed = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalBoolean("armUseConfirmed") else null,
            legacyTandemHoldSeconds = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalDouble("tandemHoldSeconds") else null,
            legacySideBySideHoldSeconds = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalDouble("sideBySideHoldSeconds") else null,
            legacySemiTandemHoldSeconds = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalDouble("semiTandemHoldSeconds") else null,
            legacyOneLegHoldSeconds = if (schemaVersion == LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) optionalDouble("oneLegHoldSeconds") else null,
        )
    }

    private fun JSONObject.toCalibration(): AssessmentCalibration {
        requireOnlyKeys("sampledDurationMs", "lFootM", "hStandM", "hSitM", "wShoulderM", "dFoldM", "supportRoiNormalized")
        return AssessmentCalibration(
            requireLong("sampledDurationMs"), requireDouble("lFootM"), requireDouble("hStandM"),
            optionalDouble("hSitM"), requireDouble("wShoulderM"), optionalDouble("dFoldM"),
            requireNullableObject("supportRoiNormalized")?.toNormalizedRoi(),
        )
    }

    private fun JSONObject.toQuality(): AssessmentQualitySummary {
        requireOnlyKeys("gates", "g3ViolationRatio", "invalidReasons", "excludeFromTrends")
        val array = requireArray("gates")
        return AssessmentQualitySummary(
            gates = buildList { for (index in 0 until array.length()) add(array.requireObject(index).toQualityGate()) },
            g3ViolationRatio = requireDouble("g3ViolationRatio"),
            invalidReasons = requireArray("invalidReasons").toStringList(),
            excludeFromTrends = requireBoolean("excludeFromTrends"),
        )
    }

    private fun JSONObject.toQualityGate(): QualityGateObservation {
        requireOnlyKeys("gate", "violationFrameCount", "violationDurationMs", "violationRatio")
        return QualityGateObservation(requireEnum("gate"), requireInt("violationFrameCount"), requireLong("violationDurationMs"), requireDouble("violationRatio"))
    }

    private fun JSONObject.toChairStand(): ChairStandMeasurements {
        requireOnlyKeys("observedRepetitions", "completedRepetitions", "cdcScoredRepetitions", "finalRepetitionCredit", "finalState", "armUse")
        return ChairStandMeasurements(
            requireInt("observedRepetitions"), requireInt("completedRepetitions"), requireInt("cdcScoredRepetitions"),
            requireInt("finalRepetitionCredit"), requireEnum("finalState"), requireObject("armUse").toArmUse(),
        )
    }

    private fun JSONObject.toArmUse(): ChairStandArmUse {
        requireOnlyKeys("occurrenceCount", "restartUsed", "outcome")
        return ChairStandArmUse(requireInt("occurrenceCount"), requireBoolean("restartUsed"), requireEnum("outcome"))
    }

    private fun JSONObject.toBalance(): BalanceMeasurements {
        requireOnlyKeys("stages")
        val array = requireArray("stages")
        return BalanceMeasurements(buildList { for (index in 0 until array.length()) add(array.requireObject(index).toBalanceStage()) })
    }

    private fun JSONObject.toBalanceStage(): BalanceStageResult {
        requireOnlyKeys("stage", "onsetLatencyMs", "holdSeconds", "status", "failureCode", "failureReason", "sway")
        return BalanceStageResult(
            stage = requireEnum("stage"), onsetLatencyMs = requireNullableLong("onsetLatencyMs"), holdSeconds = requireDouble("holdSeconds"),
            status = requireEnum("status"), failureCode = requireNullableEnum<BalanceFailureCode>("failureCode"),
            failureReason = requireNullableString("failureReason"),
            sway = requireNullableObject("sway")?.toSway(),
        )
    }

    private fun JSONObject.toSway(): BalanceSwayMetrics {
        requireOnlyKeys("mlRmsM", "apRmsM", "initialRmsM", "staticRmsM", "initialToStaticRatio", "mlToApRatio")
        return BalanceSwayMetrics(
            optionalDouble("mlRmsM"), optionalDouble("apRmsM"), optionalDouble("initialRmsM"),
            optionalDouble("staticRmsM"), optionalDouble("initialToStaticRatio"), optionalDouble("mlToApRatio"),
        )
    }

    private fun JSONObject.toVulnerabilityAssessment(): VulnerabilityAssessment {
        requireOnlyKeys("activeIds", "evidence", "ruleVersion")
        val evidenceJson = requireArray("evidence")
        return VulnerabilityAssessment(
            activeIds = requireArray("activeIds").toEnumList(),
            evidence = buildList { for (index in 0 until evidenceJson.length()) add(evidenceJson.requireObject(index).toVulnerabilityEvidence()) },
            ruleVersion = requireNonBlankString("ruleVersion"),
        )
    }

    private fun JSONObject.toVulnerabilityEvidence(): VulnerabilityEvidence {
        requireOnlyKeys("vulnerabilityId", "sourceResultId", "measurements")
        return VulnerabilityEvidence(
            requireEnum("vulnerabilityId"),
            requireNullableString("sourceResultId"),
            requireObject("measurements").toString(),
        )
    }

    private fun JSONObject.toSteadi(): SteadiScore {
        requireOnlyKeys(
            "status", "riskLevel", "strengthProblem", "balanceProblem", "step1AtRisk", "step2Problem",
            "reasonCodes", "ruleVersion",
        )
        return SteadiScore(
            status = requireEnum("status"),
            risk = requireEnum("riskLevel"),
            strengthProblem = requireNullableBoolean("strengthProblem"),
            balanceProblem = requireNullableBoolean("balanceProblem"),
            step1AtRisk = requireNullableBoolean("step1AtRisk"),
            step2Problem = requireNullableBoolean("step2Problem"),
            reasonCodes = requireArray("reasonCodes").toStringList(),
            ruleVersion = requireNonBlankString("ruleVersion"),
        )
    }

    private fun JSONObject.toPrescription(schemaVersion: String): AssessmentPrescription {
        if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireOnlyKeys("status", "plan", "sessionResults")
        else requireOnlyKeys("status", "plan")
        val status: PrescriptionStatus = requireEnum("status")
        requirePresent("plan")
        val plan = if (isNull("plan")) null else OtagoPrescriptionContract.decode(requireObject("plan"))
        val resultsJson = if (schemaVersion == ASSESSMENT_SESSION_SCHEMA_VERSION) requireArray("sessionResults") else JSONArray()
        val results = buildList {
            for (index in 0 until resultsJson.length()) add(ExerciseSessionResultJsonCodec.decodeObject(resultsJson.requireObject(index)))
        }
        return AssessmentPrescription(status, plan, results)
    }
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun JSONObject.requireExactString(name: String, expected: String) {
    if (requireNonBlankString(name) != expected) contractError("$name must be $expected")
}
private fun JSONObject.requireSupportedSchemaVersion(): String {
    val value = requireNonBlankString("schemaVersion")
    if (value != ASSESSMENT_SESSION_SCHEMA_VERSION && value != LEGACY_ASSESSMENT_SESSION_SCHEMA_VERSION) {
        contractError("Unsupported schemaVersion: $value")
    }
    return value
}
private fun JSONObject.requirePresent(name: String) {
    if (!has(name)) contractError("Missing required field: $name")
}
private fun JSONObject.requireOnlyKeys(vararg allowed: String) {
    val allowedSet = allowed.toSet()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        if (key !in allowedSet) contractError("Unsupported field: $key")
    }
}
private fun JSONObject.requireNonBlankString(name: String): String {
    requirePresent(name)
    val value = opt(name)
    if (value !is String || value.isBlank()) contractError("$name must be a non-blank string")
    return value
}
private fun JSONObject.requireNullableString(name: String): String? {
    requirePresent(name)
    if (isNull(name)) return null
    val value = opt(name)
    if (value !is String || value.isBlank()) contractError("$name must be null or non-blank string")
    return value
}
private fun JSONObject.requireLong(name: String): Long {
    requirePresent(name)
    val value = opt(name)
    if (value !is Number) contractError("$name must be a number")
    val double = value.toDouble()
    val long = value.toLong()
    if (!double.isFinite() || double != long.toDouble()) contractError("$name must be an integer")
    return long
}
private fun JSONObject.requireInt(name: String): Int = requireLong(name).also {
    if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) contractError("$name must fit Int")
}.toInt()
private fun JSONObject.requireDouble(name: String): Double {
    requirePresent(name)
    val value = opt(name)
    if (value !is Number || !value.toDouble().isFinite()) contractError("$name must be a finite number")
    return value.toDouble()
}
private fun JSONObject.requireNullableLong(name: String): Long? {
    requirePresent(name)
    if (isNull(name)) return null
    return requireLong(name)
}
private fun JSONObject.requireNullableInt(name: String): Int? = requireNullableLong(name)?.also {
    if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) contractError("$name must fit Int")
}?.toInt()
private fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return requireNullableInt(name)
}
private fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return requireDouble(name)
}
private fun JSONObject.requireBoolean(name: String): Boolean {
    requirePresent(name)
    val value = opt(name)
    if (value !is Boolean) contractError("$name must be boolean")
    return value
}
private fun JSONObject.requireNullableBoolean(name: String): Boolean? {
    requirePresent(name)
    if (isNull(name)) return null
    val value = opt(name)
    if (value !is Boolean) contractError("$name must be null or boolean")
    return value
}
private fun JSONObject.optionalBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name)
    if (value !is Boolean) contractError("$name must be boolean")
    return value
}
private fun JSONObject.requireObject(name: String): JSONObject {
    requirePresent(name)
    return optJSONObject(name) ?: contractError("$name must be an object")
}
private fun JSONObject.requireNullableObject(name: String): JSONObject? {
    requirePresent(name)
    if (isNull(name)) return null
    return requireObject(name)
}
private fun JSONObject.requireArray(name: String): JSONArray {
    requirePresent(name)
    return optJSONArray(name) ?: contractError("$name must be an array")
}
private inline fun <reified T : Enum<T>> JSONObject.requireEnum(name: String): T {
    val raw = requireNonBlankString(name)
    return enumValues<T>().firstOrNull { it.name == raw } ?: contractError("Unsupported $name: $raw")
}
private inline fun <reified T : Enum<T>> JSONObject.requireNullableEnum(name: String): T? {
    requirePresent(name)
    if (isNull(name)) return null
    return requireEnum<T>(name)
}
private fun JSONArray.requireObject(index: Int): JSONObject = optJSONObject(index)
    ?: contractError("Array item $index must be an object")
private fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = opt(index)
        if (value !is String || value.isBlank()) contractError("Array item $index must be non-blank string")
        add(value)
    }
}
private inline fun <reified T : Enum<T>> JSONArray.toEnumList(): List<T> = buildList {
    for (index in 0 until length()) {
        val raw = opt(index)
        if (raw !is String || raw.isBlank()) contractError("Array item $index must be non-blank string")
        add(enumValues<T>().firstOrNull { it.name == raw } ?: contractError("Unsupported array item $index: $raw"))
    }
}
private fun contractError(message: String): Nothing = throw AssessmentSessionContractException(message)

private const val G3_INVALIDATION_RATIO = 0.20
private const val G3_INVALID_REASON = "G3_VIOLATION_RATIO_EXCEEDED"
