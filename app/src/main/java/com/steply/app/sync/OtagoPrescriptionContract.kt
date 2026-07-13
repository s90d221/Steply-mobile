package com.steply.app.sync

import com.steply.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object OtagoPrescriptionContract {
    fun encode(plan: OtagoPrescriptionPlan): JSONObject {
        validate(plan)
        return JSONObject()
            .put("schemaVersion", plan.schemaVersion)
            .put("catalogVersion", plan.catalogVersion)
            .put("planId", plan.planId)
            .put("userId", plan.userId)
            .put("riskLevel", plan.riskLevel.name)
            .put("status", plan.status.name)
            .put("vulnerabilityIds", enumArray(plan.vulnerabilityIds))
            .put("warmups", JSONArray().also { values -> plan.warmups.forEach { values.put(it.toJson()) } })
            .put("selectedExercises", JSONArray().also { values -> plan.selectedExercises.forEach { values.put(it.toJson()) } })
            .put("walkingPlan", plan.walkingPlan?.toJson() ?: JSONObject.NULL)
            .put("professionalApproval", plan.professionalApproval.toJson())
            .put("supervisionRequirement", plan.supervisionRequirement.name)
            .put("caregiverRecommendedDays", plan.caregiverRecommendedDays)
            .put("requiresProfessionalReview", plan.requiresProfessionalReview)
            .put("safetyNotices", JSONArray(plan.safetyNotices))
            .put("progressionProposals", JSONArray().also { values -> plan.progressionProposals.forEach { values.put(it.toJson()) } })
            .put("sourceAssessmentIds", JSONArray(plan.sourceAssessmentIds))
            .put("sourceResultIds", JSONArray(plan.sourceResultIds))
            .put("generatedByRuleVersion", plan.generatedByRuleVersion)
            .put("decisionTrace", JSONArray(plan.decisionTrace))
    }

    fun decode(json: JSONObject): OtagoPrescriptionPlan {
        json.only(
            "schemaVersion", "catalogVersion", "planId", "userId", "riskLevel", "status",
            "vulnerabilityIds", "warmups", "selectedExercises", "walkingPlan", "professionalApproval",
            "supervisionRequirement", "caregiverRecommendedDays", "requiresProfessionalReview", "safetyNotices",
            "progressionProposals", "sourceAssessmentIds", "sourceResultIds", "generatedByRuleVersion", "decisionTrace",
        )
        val plan = OtagoPrescriptionPlan(
            schemaVersion = json.string("schemaVersion"),
            catalogVersion = json.string("catalogVersion"),
            planId = json.string("planId"),
            userId = json.string("userId"),
            riskLevel = json.enum("riskLevel"),
            status = json.enum("status"),
            vulnerabilityIds = json.array("vulnerabilityIds").enums(),
            warmups = json.array("warmups").objects { it.toPrescribedExercise() },
            selectedExercises = json.array("selectedExercises").objects { it.toPrescribedExercise() },
            walkingPlan = json.nullableObject("walkingPlan")?.toWalkingPlan(),
            professionalApproval = json.objectValue("professionalApproval").toProfessionalApproval(),
            supervisionRequirement = json.enum("supervisionRequirement"),
            caregiverRecommendedDays = json.int("caregiverRecommendedDays"),
            requiresProfessionalReview = json.boolean("requiresProfessionalReview"),
            safetyNotices = json.array("safetyNotices").strings(),
            progressionProposals = json.array("progressionProposals").objects { it.toProgressionProposal() },
            sourceAssessmentIds = json.array("sourceAssessmentIds").strings(),
            sourceResultIds = json.array("sourceResultIds").strings(),
            generatedByRuleVersion = json.string("generatedByRuleVersion"),
            decisionTrace = json.array("decisionTrace").strings(),
        )
        validate(plan)
        return plan
    }

    fun validate(plan: OtagoPrescriptionPlan) {
        if (plan.schemaVersion != OTAGO_PRESCRIPTION_SCHEMA_VERSION) fail("Prescription schemaVersion must be $OTAGO_PRESCRIPTION_SCHEMA_VERSION")
        if (plan.catalogVersion != OTAGO_CATALOG_VERSION) fail("Prescription catalogVersion must be $OTAGO_CATALOG_VERSION")
        if (plan.planId.isBlank() || plan.userId.isBlank() || plan.generatedByRuleVersion.isBlank()) fail("Prescription identifiers and rule version must not be blank")
        if (plan.riskLevel == SteadiRisk.NOT_SCORABLE) fail("Prescription riskLevel must be scorable")
        uniqueEnums(plan.vulnerabilityIds, "vulnerabilityIds")
        uniqueStrings(plan.safetyNotices, "safetyNotices")
        nonEmptyUniqueStrings(plan.decisionTrace, "decisionTrace")
        nonEmptyUniqueStrings(plan.sourceAssessmentIds, "sourceAssessmentIds")
        nonEmptyUniqueStrings(plan.sourceResultIds, "sourceResultIds")
        if (plan.caregiverRecommendedDays < 0) fail("caregiverRecommendedDays must be non-negative")

        val allExercises = plan.warmups + plan.selectedExercises
        if (allExercises.map { it.exerciseId }.distinct().size != allExercises.size) fail("Prescription exercise IDs must be unique")
        plan.warmups.forEach { exercise ->
            if (exercise.exerciseId !in OTAGO_WARMUP_IDS) fail("warmups may contain only W1 through W5")
            validateExercise(exercise, plan.vulnerabilityIds)
        }
        plan.selectedExercises.forEach { validateExercise(it, plan.vulnerabilityIds) }
        plan.walkingPlan?.let(::validateWalkingPlan)
        validateProfessionalApproval(plan.professionalApproval)
        plan.progressionProposals.forEach { validateProgression(it, allExercises.map { item -> item.exerciseId }) }
        if (plan.progressionProposals.map { it.proposalId }.distinct().size != plan.progressionProposals.size) {
            fail("progression proposal IDs must be unique")
        }

        if (plan.warmups.map { it.exerciseId } != OTAGO_WARMUP_IDS) {
            fail("Plans require W1 through W5 in canonical order")
        }
        when (plan.status) {
            OtagoPlanStatus.ACTIVE -> {
                if (plan.riskLevel != SteadiRisk.HIGH && plan.walkingPlan == null) {
                    fail("ACTIVE LOW and MODERATE plans require the walking plan")
                }
            }
            OtagoPlanStatus.PENDING_PROFESSIONAL_REVIEW -> Unit
            OtagoPlanStatus.BLOCKED -> {
                if (plan.selectedExercises.isNotEmpty() || plan.walkingPlan != null || plan.progressionProposals.isNotEmpty()) {
                    fail("Blocked plans may preserve canonical warmups but cannot expose selected exercise, walking, or progression execution")
                }
            }
        }

        when (plan.riskLevel) {
            SteadiRisk.LOW -> {
                if (plan.requiresProfessionalReview || plan.professionalApproval.status != ApprovalStatus.NOT_REQUIRED) {
                    fail("LOW plans cannot require professional approval")
                }
                if (plan.supervisionRequirement != SupervisionRequirement.NONE || plan.caregiverRecommendedDays != 0) {
                    fail("LOW plans cannot add a risk-based supervision requirement")
                }
                plan.selectedExercises.filter { it.category == ExerciseCategory.BALANCE }.forEach {
                    if (it.level !in setOf(ExerciseLevel.A, ExerciseLevel.B)) fail("LOW balance is capped at Level B")
                }
            }
            SteadiRisk.MODERATE -> {
                if (plan.requiresProfessionalReview || plan.professionalApproval.status != ApprovalStatus.NOT_REQUIRED) {
                    fail("MODERATE plans cannot require professional approval")
                }
                if (plan.supervisionRequirement != SupervisionRequirement.CAREGIVER_RECOMMENDED || plan.caregiverRecommendedDays < 14) {
                    fail("MODERATE plans require caregiver recommendation for the first 14 days")
                }
                plan.selectedExercises.forEach {
                    if (it.category == ExerciseCategory.BALANCE && it.level != ExerciseLevel.A) fail("MODERATE balance is capped at Level A")
                    if (it.category == ExerciseCategory.STRENGTH && it.weightMode != WeightMode.NONE &&
                        (it.weightMinKg == null || it.weightMinKg < 1.0 || it.weightMaxKg == null || it.weightMaxKg > 2.0)
                    ) fail("MODERATE weighted strength must start within 1 to 2 kg")
                }
            }
            SteadiRisk.HIGH -> validateHighPlan(plan)
            SteadiRisk.NOT_SCORABLE -> Unit
        }

        if (VulnerabilityId.V6 in plan.vulnerabilityIds && plan.status == OtagoPlanStatus.ACTIVE) {
            if (allExercises.any { it.level != it.exerciseId.catalogMinimumLevel() || it.weightMode != WeightMode.NONE }) {
                fail("V6 requires the lowest exercise level without weight")
            }
        }
        if (VulnerabilityId.V7 in plan.vulnerabilityIds && plan.status == OtagoPlanStatus.ACTIVE) {
            if (plan.selectedExercises.filter { it.category == ExerciseCategory.BALANCE }.any {
                    it.level != ExerciseLevel.A || it.supportRequirement == SupportRequirement.NONE
                }
            ) fail("V7 requires supported Level A balance")
            if (plan.supervisionRequirement == SupervisionRequirement.NONE) fail("V7 requires supervision")
        }
    }

    private fun ExerciseId.catalogMinimumLevel(): ExerciseLevel = when (this) {
        ExerciseId.S4, ExerciseId.S5 -> ExerciseLevel.C
        else -> ExerciseLevel.A
    }

    private fun ExerciseId.hasCanonicalVariant(variantId: String, level: ExerciseLevel): Boolean {
        val allowedLevels = when (this) {
            ExerciseId.W1, ExerciseId.W2, ExerciseId.W3, ExerciseId.W4, ExerciseId.W5,
            ExerciseId.B10 -> setOf(ExerciseLevel.A)
            ExerciseId.S1, ExerciseId.S2, ExerciseId.S3, ExerciseId.B7 ->
                setOf(ExerciseLevel.A, ExerciseLevel.B, ExerciseLevel.C)
            ExerciseId.S4, ExerciseId.S5 -> setOf(ExerciseLevel.C, ExerciseLevel.D)
            ExerciseId.B1, ExerciseId.B12 -> ExerciseLevel.entries.toSet()
            ExerciseId.B2, ExerciseId.B3, ExerciseId.B4, ExerciseId.B5, ExerciseId.B6,
            ExerciseId.B8, ExerciseId.B9, ExerciseId.B11 -> setOf(ExerciseLevel.A, ExerciseLevel.B)
            ExerciseId.WALK -> emptySet()
        }
        return level in allowedLevels && variantId == "${name}-${level.name}"
    }

    private fun validateHighPlan(plan: OtagoPrescriptionPlan) {
        if (plan.supervisionRequirement != SupervisionRequirement.PROFESSIONAL_REVIEW_REQUIRED) {
            fail("HIGH plans require professional supervision")
        }
        if (plan.walkingPlan != null) fail("HIGH plans cannot include a walking plan")
        if (plan.warmups.any { it.level != ExerciseLevel.A || it.weightMode != WeightMode.NONE }) {
            fail("HIGH warmups must remain Level A without weight")
        }
        validateHighExerciseCaps(plan.selectedExercises)
        when (plan.professionalApproval.status) {
            ApprovalStatus.PENDING -> {
                if (plan.status != OtagoPlanStatus.PENDING_PROFESSIONAL_REVIEW || !plan.requiresProfessionalReview) {
                    fail("Unapproved HIGH plans must remain pending")
                }
            }
            ApprovalStatus.APPROVED -> {
                if (plan.status != OtagoPlanStatus.ACTIVE || plan.requiresProfessionalReview) {
                    fail("Approved HIGH plans must be ACTIVE without a pending review block")
                }
                if (plan.professionalApproval.approvedByRole != ApprovalActorRole.PROFESSIONAL) fail("HIGH approval requires a professional")
                if (plan.progressionProposals.isNotEmpty()) fail("HIGH plans cannot progress beyond Level A")
            }
            ApprovalStatus.NOT_REQUIRED -> fail("HIGH plans require professional approval")
        }
    }

    private fun validateHighExerciseCaps(exercises: List<PrescribedExercise>) {
        exercises.forEach { exercise ->
            if (exercise.weightMode != WeightMode.NONE) fail("HIGH exercises must not use weight")
            when {
                exercise.category == ExerciseCategory.BALANCE && exercise.level != ExerciseLevel.A -> {
                    fail("HIGH balance must remain Level A")
                }
                exercise.exerciseId in setOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3) && exercise.level != ExerciseLevel.A -> {
                    fail("HIGH S1 through S3 must remain Level A")
                }
                exercise.exerciseId in setOf(ExerciseId.S4, ExerciseId.S5) &&
                    (exercise.level != ExerciseLevel.C || exercise.supportRequirement != SupportRequirement.STABLE_SUPPORT) -> {
                    fail("HIGH S4 and S5 require the supported Level C catalog variant")
                }
            }
        }
    }

    private fun validateExercise(value: PrescribedExercise, activeVulnerabilities: List<VulnerabilityId>) {
        if (value.exerciseId == ExerciseId.WALK || value.category != value.exerciseId.expectedCategory()) fail("Exercise category does not match exerciseId")
        if (value.variantId.isBlank() || value.displayName.isBlank()) fail("Exercise variantId and displayName must not be blank")
        if (!value.exerciseId.hasCanonicalVariant(value.variantId, value.level)) {
            fail("Exercise variantId and level must match the canonical Otago catalog")
        }
        listOf(value.repetitions, value.sets, value.repetitionsPerSide, value.steps, value.holdSeconds)
            .filterNotNull().forEach { if (it <= 0) fail("Exercise dosage values must be positive") }
        listOf(value.restMinSeconds, value.restMaxSeconds).filterNotNull().forEach {
            if (it < 0) fail("Exercise rest values must be non-negative")
        }
        if (listOf(value.repetitions, value.repetitionsPerSide, value.steps, value.holdSeconds).all { it == null }) {
            fail("Exercise requires a repetition, step, or hold dosage")
        }
        if (value.breathingRule.isBlank()) fail("breathingRule must not be blank")
        uniqueEnums(value.reasonVulnerabilityIds, "reasonVulnerabilityIds")
        if (value.reasonVulnerabilityIds.any { it !in activeVulnerabilities }) fail("Exercise cites an inactive vulnerability")
        if (value.category != ExerciseCategory.WARMUP && value.reasonVulnerabilityIds.isEmpty()) fail("Selected exercise requires vulnerability evidence")
        if (value.weakSideExtraSets < 0 || (value.weakSideExtraSets > 0 && VulnerabilityId.V9 !in value.reasonVulnerabilityIds)) {
            fail("weakSideExtraSets requires V9")
        }
        when (value.weightMode) {
            WeightMode.NONE -> if (value.weightMinKg != null || value.weightMaxKg != null) fail("NONE weight cannot contain kilograms")
            WeightMode.ANKLE_CUFF, WeightMode.FATIGUE_TARGET -> {
                val min = value.weightMinKg ?: fail("Weighted exercise requires weightMinKg")
                val max = value.weightMaxKg ?: fail("Weighted exercise requires weightMaxKg")
                if (!min.isFinite() || !max.isFinite() || min <= 0.0 || max < min) fail("Weight range is invalid")
            }
        }
        val tempo = listOf(value.tempoUpMinSeconds, value.tempoUpMaxSeconds, value.tempoDownMinSeconds, value.tempoDownMaxSeconds)
        if (tempo.any { it != null } && tempo.any { it == null }) fail("Tempo must provide all four bounds or none")
        tempo.filterNotNull().forEach { if (!it.isFinite() || it <= 0.0) fail("Tempo values must be positive") }
        if (value.tempoUpMinSeconds != null && (value.tempoUpMaxSeconds!! < value.tempoUpMinSeconds || value.tempoDownMaxSeconds!! < value.tempoDownMinSeconds!!)) {
            fail("Tempo ranges are invalid")
        }
        if (value.restMinSeconds != null && value.restMaxSeconds != null &&
            value.restMaxSeconds < value.restMinSeconds
        ) fail("Rest range is invalid")
        if (value.category == ExerciseCategory.STRENGTH) {
            if (value.tempoUpMinSeconds != 2.0 || value.tempoUpMaxSeconds != 3.0 ||
                value.tempoDownMinSeconds != 4.0 || value.tempoDownMaxSeconds != 5.0
            ) fail("Strength tempo must be 2-3 seconds up and 4-5 seconds down")
            if (value.restMinSeconds != 60 || value.restMaxSeconds != 120) fail("Strength rest must be 60-120 seconds")
            if (value.cameraVerification != CameraVerification.FULL) fail("S1 through S5 require full camera verification")
        }
        if (value.category == ExerciseCategory.BALANCE) {
            val expected = when (value.exerciseId) {
                ExerciseId.B1, ExerciseId.B5, ExerciseId.B7, ExerciseId.B11 -> CameraVerification.FULL
                ExerciseId.B4 -> CameraVerification.PARTIAL
                else -> CameraVerification.MANUAL_ONLY
            }
            if (value.cameraVerification != expected) fail("Balance camera verification does not match the catalog")
        }
    }

    private fun validateWalkingPlan(value: WalkingPlan) {
        if (value.exerciseId != ExerciseId.WALK || value.category != ExerciseCategory.WALKING) fail("walkingPlan must use WALK")
        if (value.targetMinutes != 30 || value.splitMinutes != listOf(10, 10, 10) || value.weeklyFrequency < 2 ||
            value.pace != "USUAL" || !value.requiresStrengthAndBalance || value.cameraVerification != CameraVerification.MANUAL_ONLY
        ) fail("Walking plan must preserve the Otago 30-minute, 10x3, twice-weekly usual-pace rule")
    }

    private fun validateProfessionalApproval(value: ProfessionalApproval) {
        when (value.status) {
            ApprovalStatus.NOT_REQUIRED, ApprovalStatus.PENDING -> if (value.approvalId != null || value.approvedByRole != null || value.approvedAt != null) {
                fail("Unapproved professionalApproval cannot contain approval evidence")
            }
            ApprovalStatus.APPROVED -> if (value.approvalId.isNullOrBlank() || value.approvedByRole == null || value.approvedAt == null || value.approvedAt < 0L) {
                fail("Approved professionalApproval requires identity, role, and timestamp")
            }
        }
    }

    private fun validateProgression(value: ProgressionProposal, prescribedIds: List<ExerciseId>) {
        if (value.proposalId.isBlank() || value.fromVariantId.isBlank() || value.toVariantId.isBlank()) fail("Progression identifiers must not be blank")
        if (value.exerciseId !in prescribedIds) fail("Progression exercise must be prescribed")
        when (value.progressionType) {
            ProgressionType.INCREASE_WEIGHT -> {
                if (value.exerciseId !in setOf(ExerciseId.S1, ExerciseId.S2, ExerciseId.S3)) fail("Only S1 through S3 may progress by weight")
                if (value.fromLevel != value.toLevel || value.fromVariantId != value.toVariantId) fail("Weight progression keeps the same level and variant")
                if (value.weightIncrementMinKg != 0.5 || value.weightIncrementMaxKg != 1.0) fail("Weight progression must add 0.5 to 1 kg")
            }
            ProgressionType.REMOVE_SUPPORT -> {
                if (value.toLevel.ordinal <= value.fromLevel.ordinal) fail("Support removal must increase the level")
                requireNoWeightIncrement(value)
            }
            ProgressionType.ADVANCE_VARIANT -> {
                if (value.fromVariantId == value.toVariantId || value.toLevel.ordinal < value.fromLevel.ordinal) fail("Variant progression requires a new non-regressive variant")
                requireNoWeightIncrement(value)
            }
            ProgressionType.INCREASE_SETS -> {
                if (value.fromLevel != value.toLevel || value.fromVariantId != value.toVariantId) fail("Set progression keeps the same level and variant")
                requireNoWeightIncrement(value)
            }
        }
        if (value.qualifyingSessionIds.size != 2 || value.qualifyingSessionIds.any { it.isBlank() } || value.qualifyingSessionIds.distinct().size != 2) {
            fail("Progression requires exactly two distinct accurate sessions")
        }
        when (value.status) {
            ProgressionStatus.PENDING_APPROVAL -> if (value.approval != null) fail("Pending progression cannot already be approved")
            ProgressionStatus.APPROVED, ProgressionStatus.APPLIED -> {
                val approval = value.approval ?: fail("Approved progression requires user or caregiver approval")
                if (approval.approvedBy.isBlank() || approval.approvedAt < 0L) fail("Progression approval evidence is invalid")
            }
        }
    }

    private fun requireNoWeightIncrement(value: ProgressionProposal) {
        if (value.weightIncrementMinKg != null || value.weightIncrementMaxKg != null) fail("Only INCREASE_WEIGHT may include weight increments")
    }
}

private fun PrescribedExercise.toJson() = JSONObject()
    .put("exerciseId", exerciseId.name).put("category", category.name).put("level", level.name)
    .put("variantId", variantId).put("displayName", displayName)
    .putNullable("repetitions", repetitions).putNullable("sets", sets).putNullable("repetitionsPerSide", repetitionsPerSide)
    .putNullable("steps", steps).putNullable("holdSeconds", holdSeconds).put("supportRequirement", supportRequirement.name)
    .put("weightMode", weightMode.name).putNullable("weightMinKg", weightMinKg).putNullable("weightMaxKg", weightMaxKg)
    .putNullable("tempoUpMinSeconds", tempoUpMinSeconds).putNullable("tempoUpMaxSeconds", tempoUpMaxSeconds)
    .putNullable("tempoDownMinSeconds", tempoDownMinSeconds).putNullable("tempoDownMaxSeconds", tempoDownMaxSeconds)
    .put("breathingRule", breathingRule).putNullable("restMinSeconds", restMinSeconds).putNullable("restMaxSeconds", restMaxSeconds)
    .put("cameraVerification", cameraVerification.name).put("reasonVulnerabilityIds", enumArray(reasonVulnerabilityIds))
    .put("weakSideExtraSets", weakSideExtraSets)

private fun JSONObject.toPrescribedExercise(): PrescribedExercise {
    only(
        "exerciseId", "category", "level", "variantId", "displayName", "repetitions", "sets", "repetitionsPerSide", "steps", "holdSeconds",
        "supportRequirement", "weightMode", "weightMinKg", "weightMaxKg", "tempoUpMinSeconds", "tempoUpMaxSeconds",
        "tempoDownMinSeconds", "tempoDownMaxSeconds", "breathingRule", "restMinSeconds", "restMaxSeconds",
        "cameraVerification", "reasonVulnerabilityIds", "weakSideExtraSets",
    )
    return PrescribedExercise(
        enum("exerciseId"), enum("category"), enum("level"), string("variantId"), string("displayName"), nullableInt("repetitions"), nullableInt("sets"),
        nullableInt("repetitionsPerSide"), nullableInt("steps"), nullableInt("holdSeconds"), enum("supportRequirement"),
        enum("weightMode"), nullableDouble("weightMinKg"), nullableDouble("weightMaxKg"), nullableDouble("tempoUpMinSeconds"),
        nullableDouble("tempoUpMaxSeconds"), nullableDouble("tempoDownMinSeconds"), nullableDouble("tempoDownMaxSeconds"),
        string("breathingRule"), nullableInt("restMinSeconds"), nullableInt("restMaxSeconds"), enum("cameraVerification"),
        array("reasonVulnerabilityIds").enums(), int("weakSideExtraSets"),
    )
}

private fun WalkingPlan.toJson() = JSONObject().put("exerciseId", exerciseId.name).put("category", category.name)
    .put("targetMinutes", targetMinutes).put("splitMinutes", JSONArray(splitMinutes)).put("weeklyFrequency", weeklyFrequency)
    .put("pace", pace).put("requiresStrengthAndBalance", requiresStrengthAndBalance).put("cameraVerification", cameraVerification.name)

private fun JSONObject.toWalkingPlan(): WalkingPlan {
    only("exerciseId", "category", "targetMinutes", "splitMinutes", "weeklyFrequency", "pace", "requiresStrengthAndBalance", "cameraVerification")
    return WalkingPlan(enum("exerciseId"), enum("category"), int("targetMinutes"), array("splitMinutes").ints(), int("weeklyFrequency"), string("pace"), boolean("requiresStrengthAndBalance"), enum("cameraVerification"))
}

private fun ProfessionalApproval.toJson() = JSONObject().put("status", status.name).putNullable("approvalId", approvalId)
    .putNullable("approvedByRole", approvedByRole?.name).putNullable("approvedAt", approvedAt)

private fun JSONObject.toProfessionalApproval(): ProfessionalApproval {
    only("status", "approvalId", "approvedByRole", "approvedAt")
    return ProfessionalApproval(
        status = enum<ApprovalStatus>("status"),
        approvalId = nullableString("approvalId"),
        approvedByRole = nullableEnum<ApprovalActorRole>("approvedByRole"),
        approvedAt = nullableLong("approvedAt"),
    )
}

private fun ProgressionProposal.toJson() = JSONObject().put("proposalId", proposalId).put("exerciseId", exerciseId.name)
    .put("fromLevel", fromLevel.name).put("toLevel", toLevel.name).put("fromVariantId", fromVariantId).put("toVariantId", toVariantId)
    .put("progressionType", progressionType.name).putNullable("weightIncrementMinKg", weightIncrementMinKg).putNullable("weightIncrementMaxKg", weightIncrementMaxKg)
    .put("status", status.name).put("qualifyingSessionIds", JSONArray(qualifyingSessionIds)).put("approval", approval?.toJson() ?: JSONObject.NULL)

private fun JSONObject.toProgressionProposal(): ProgressionProposal {
    only("proposalId", "exerciseId", "fromLevel", "toLevel", "fromVariantId", "toVariantId", "progressionType", "weightIncrementMinKg", "weightIncrementMaxKg", "status", "qualifyingSessionIds", "approval")
    return ProgressionProposal(
        string("proposalId"), enum("exerciseId"), enum("fromLevel"), enum("toLevel"), string("fromVariantId"), string("toVariantId"),
        enum("progressionType"), nullableDouble("weightIncrementMinKg"), nullableDouble("weightIncrementMaxKg"), enum("status"),
        array("qualifyingSessionIds").strings(), nullableObject("approval")?.toProgressionApproval(),
    )
}

private fun ProgressionApproval.toJson() = JSONObject().put("actor", actor.name).put("approvedBy", approvedBy).put("approvedAt", approvedAt)
private fun JSONObject.toProgressionApproval(): ProgressionApproval {
    only("actor", "approvedBy", "approvedAt")
    return ProgressionApproval(enum("actor"), string("approvedBy"), long("approvedAt"))
}

private fun enumArray(values: List<Enum<*>>) = JSONArray(values.map { it.name })
private fun JSONObject.putNullable(name: String, value: Any?) = put(name, value ?: JSONObject.NULL)
private fun JSONObject.only(vararg allowed: String) {
    val allowedSet = allowed.toSet()
    for (key in keys()) if (key !in allowedSet) fail("Unsupported prescription field: $key")
    allowed.forEach { if (!has(it)) fail("Missing prescription field: $it") }
}
private fun JSONObject.string(name: String): String = opt(name).let { if (it !is String || it.isBlank()) fail("$name must be non-blank") else it }
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else string(name)
private fun JSONObject.boolean(name: String): Boolean = opt(name).let { if (it !is Boolean) fail("$name must be boolean") else it }
private fun JSONObject.long(name: String): Long = opt(name).let {
    if (it !is Number || !it.toDouble().isFinite() || it.toDouble() != it.toLong().toDouble()) fail("$name must be an integer") else it.toLong()
}
private fun JSONObject.int(name: String): Int = long(name).let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$name must fit Int") else it.toInt() }
private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else long(name)
private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else int(name)
private fun JSONObject.nullableDouble(name: String): Double? = if (isNull(name)) null else opt(name).let { if (it !is Number || !it.toDouble().isFinite()) fail("$name must be finite") else it.toDouble() }
private fun JSONObject.objectValue(name: String): JSONObject = optJSONObject(name) ?: fail("$name must be an object")
private fun JSONObject.nullableObject(name: String): JSONObject? = if (isNull(name)) null else objectValue(name)
private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: fail("$name must be an array")
private inline fun <reified T : Enum<T>> JSONObject.enum(name: String): T = enumValues<T>().firstOrNull { it.name == string(name) } ?: fail("Unsupported $name")
private inline fun <reified T : Enum<T>> JSONObject.nullableEnum(name: String): T? = if (isNull(name)) null else enum<T>(name)
private inline fun <reified T : Enum<T>> JSONArray.enums(): List<T> = List(length()) { index ->
    val raw = opt(index)
    if (raw !is String) fail("Enum array item must be a string")
    enumValues<T>().firstOrNull { it.name == raw } ?: fail("Unsupported enum array item: $raw")
}
private fun JSONArray.strings(): List<String> = List(length()) { index ->
    val value = opt(index)
    if (value !is String || value.isBlank()) fail("String array item must be non-blank")
    value
}
private fun JSONArray.ints(): List<Int> = List(length()) { index ->
    val value = opt(index)
    if (value !is Number || value.toDouble() != value.toInt().toDouble()) fail("Integer array item is invalid")
    value.toInt()
}
private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> = List(length()) { index -> transform(optJSONObject(index) ?: fail("Object array item is invalid")) }
private fun uniqueStrings(values: List<String>, name: String) { if (values.any { it.isBlank() } || values.distinct().size != values.size) fail("$name must contain unique non-blank values") }
private fun nonEmptyUniqueStrings(values: List<String>, name: String) { uniqueStrings(values, name); if (values.isEmpty()) fail("$name must not be empty") }
private fun uniqueEnums(values: List<Enum<*>>, name: String) { if (values.distinct().size != values.size) fail("$name must be unique") }
private fun fail(message: String): Nothing = throw AssessmentSessionContractException(message)
