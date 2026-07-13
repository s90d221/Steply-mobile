package com.steply.app.care

import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.VulnerabilityId
import org.json.JSONArray
import org.json.JSONObject

object CareStateJsonCodec {
    fun encodeInput(value: CareInputState): String = value.toJson().toString()

    fun decodeInput(raw: String): CareInputState = JSONObject(raw).toInput()

    fun encodeEvent(value: CareEvent): String = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("eventId", value.eventId)
        .put("profileId", value.profileId)
        .put("type", value.type.wireValue)
        .put("sourceEventId", value.sourceEventId)
        .put("occurredAt", value.occurredAt)
        .put("payload", JSONObject(encodeEventPayload(value.payload)))
        .toString()

    fun decodeEvent(raw: String): CareEvent {
        val json = JSONObject(raw)
        json.requireOnlyKeys("schemaVersion", "eventId", "profileId", "type", "sourceEventId", "occurredAt", "payload")
        require(json.getString("schemaVersion") == CARE_AGENT_EVENT_SCHEMA_VERSION)
        val payloadJson = json.getJSONObject("payload")
        val payload = payloadJson.keys().asSequence().associateWith { key ->
            payloadJson.getString(key).also { require(it.isNotBlank()) }
        }
        return CareEvent(
            eventId = json.getString("eventId").also { require(it.isNotBlank()) },
            profileId = json.getString("profileId").also { require(it.isNotBlank()) },
            type = CareEventType.entries.first { it.wireValue == json.getString("type") },
            sourceEventId = json.getString("sourceEventId").also { require(it.isNotBlank()) },
            occurredAt = json.getLong("occurredAt").also { require(it >= 0L) },
            payload = payload,
            schemaVersion = json.getString("schemaVersion"),
        )
    }

    fun encodeEventPayload(value: Map<String, String>): String = JSONObject().apply {
        value.toSortedMap().forEach { (key, item) -> put(key, item) }
    }.toString()

    fun encodeCandidates(values: List<CareActionCandidate>): String =
        JSONArray(values.map { it.toJson() }).toString()

    fun decodeCandidates(raw: String): List<CareActionCandidate> = JSONArray(raw).objects().map { json ->
        json.requireOnlyKeys(
            "schemaVersion", "actionId", "idempotencyKey", "eventId", "profileId", "branch",
            "actionType", "toolId", "target", "reasonCodes", "payload",
        )
        require(json.getString("schemaVersion") == CARE_AGENT_ACTION_SCHEMA_VERSION)
        val payload = json.getJSONObject("payload")
        payload.requireOnlyKeys(
            "scheduledAtMs", "messageTemplateId", "recipientId", "reportPeriodStartMs", "reportPeriodEndMs", "parameters",
        )
        val parametersJson = payload.getJSONObject("parameters")
        CareActionCandidate(
            actionId = json.getString("actionId"),
            idempotencyKey = json.getString("idempotencyKey"),
            eventId = json.getString("eventId"),
            profileId = json.getString("profileId"),
            branch = CareDecisionBranch.entries.first { it.wireValue == json.getString("branch") },
            actionType = CareActionType.entries.first { it.wireValue == json.getString("actionType") },
            toolId = CareToolId.entries.first { it.wireValue == json.getString("toolId") },
            target = json.getString("target"),
            reasonCodes = json.getJSONArray("reasonCodes").strings(),
            payload = CareActionPayload(
                scheduledAtMs = payload.nullableLong("scheduledAtMs"),
                messageTemplateId = payload.nullableString("messageTemplateId"),
                recipientId = payload.nullableString("recipientId"),
                reportPeriodStartMs = payload.nullableLong("reportPeriodStartMs"),
                reportPeriodEndMs = payload.nullableLong("reportPeriodEndMs"),
                parameters = parametersJson.keys().asSequence().associateWith(parametersJson::getString),
            ),
            schemaVersion = json.getString("schemaVersion"),
        )
    }

    fun encodeGuardrails(values: List<CareGuardrailEvaluation>): String =
        JSONArray(values.map { evaluation ->
            JSONObject()
                .put("actionId", evaluation.actionId)
                .put("allowed", evaluation.allowed)
                .put(
                    "checks",
                    JSONArray(evaluation.checks.map { check ->
                        JSONObject()
                            .put("guardrailId", check.guardrailId)
                            .put("passed", check.passed)
                            .put("reasonCode", check.reasonCode)
                    }),
                )
        }).toString()

    fun encodeCandidateDecisions(values: List<CareCandidateDecision>): String =
        JSONArray(values.map { value ->
            JSONObject()
                .put("actionId", value.actionId)
                .put("disposition", value.disposition.name)
                .put("reasonCode", value.reasonCode)
        }).toString()

    fun encodeExecutions(values: List<CareActionExecution>): String =
        JSONArray(values.map { value ->
            JSONObject()
                .put("schemaVersion", value.schemaVersion)
                .put("actionId", value.actionId)
                .put("toolId", value.toolId.wireValue)
                .put("status", value.status.name)
                .put(
                    "result",
                    value.result?.let { result ->
                        JSONObject()
                            .put("success", result.success)
                            .put("resultCode", result.resultCode)
                            .putNullable("resultReference", result.resultReference)
                            .put("retryable", result.retryable)
                    } ?: JSONObject.NULL,
                )
        }).toString()

    fun encodeStages(values: List<CareLoopStage>): String = JSONArray(values.map { it.name }).toString()

    fun encodeActionRequest(candidate: CareActionCandidate): String = candidate.toJson().toString()

    fun encodeToolResult(result: CareToolResult, recordedAt: Long): JSONObject = JSONObject()
        .put("success", result.success)
        .put("resultCode", result.resultCode)
        .putNullable("resultReference", result.resultReference)
        .put("retryable", result.retryable)
        .put("recordedAt", recordedAt)

    private fun CareInputState.toJson() = JSONObject()
        .put("profile", JSONObject()
            .put("profileId", profile.profileId)
            .put("birthYear", profile.birthYear)
            .putNullable("sex", profile.sex)
            .put("sourceUpdatedAt", profile.sourceUpdatedAt))
        .put("canonicalClinicalReference", canonicalClinicalReference.toJson())
        .put("recentAssessments", JSONArray(recentAssessments.map { it.toJson() }))
        .put("trend", JSONObject()
            .put("declining", trend.declining)
            .put("consecutiveDeclines", trend.consecutiveDeclines))
        .put("adherence", JSONObject()
            .put("completedSessionsByWeek", JSONArray(adherence.completedSessionsByWeek))
            .put("targetSessionsPerWeek", adherence.targetSessionsPerWeek)
            .put("consecutiveLowWeeks", adherence.consecutiveLowWeeks))
        .put("safetyEvents", JSONArray(safetyEvents.map { it.toJson() }))
        .put("fallReports", JSONArray(fallReports.map { it.toJson() }))
        .put("invalidAttemptNumerator", invalidAttemptNumerator)
        .put("invalidAttemptDenominator", invalidAttemptDenominator)
        .put("invalidAttemptRatio", invalidAttemptRatio)
        .put("reassessmentDueAt", reassessmentDueAt)
        .putNullable("nextPlannedSessionAt", nextPlannedSessionAt)
        .put("progressionEligible", progressionEligible)
        .put("caregiverNotificationsConsented", caregiverNotificationsConsented)
        .put("perceivedAt", perceivedAt)

    private fun CareCanonicalClinicalReference.toJson() = JSONObject()
        .put("assessmentSessionId", assessmentSessionId)
        .put("assessmentRevision", assessmentRevision)
        .put("steadiRuleVersion", steadiRuleVersion)
        .put("risk", risk.name)
        .putNullable("vulnerabilityRuleVersion", vulnerabilityRuleVersion)
        .put("vulnerabilityIds", JSONArray(vulnerabilityIds.sortedBy { it.ordinal }.map { it.name }))
        .putNullable("prescriptionPlanId", prescriptionPlanId)
        .putNullable("prescriptionSchemaVersion", prescriptionSchemaVersion)
        .put("professionalApprovalStatus", professionalApprovalStatus.name)
        .putNullable("professionalApprovalId", professionalApprovalId)

    private fun CareAssessmentSummary.toJson() = JSONObject()
        .put("assessmentSessionId", assessmentSessionId)
        .put("completedAt", completedAt)
        .put("chairStandRepetitions", chairStandRepetitions)
        .put("tandemHoldSeconds", tandemHoldSeconds)
        .put("valid", valid)

    private fun CareSafetyEventSnapshot.toJson() = JSONObject()
        .put("eventId", eventId).put("type", type).put("occurredAt", occurredAt).put("active", active)

    private fun CareFallReportSnapshot.toJson() = JSONObject()
        .put("eventId", eventId).put("occurredAt", occurredAt).put("injurious", injurious).put("unresolved", unresolved)

    private fun CareActionCandidate.toJson() = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("actionId", actionId)
        .put("idempotencyKey", idempotencyKey)
        .put("eventId", eventId)
        .put("profileId", profileId)
        .put("branch", branch.wireValue)
        .put("actionType", actionType.wireValue)
        .put("toolId", toolId.wireValue)
        .put("target", target)
        .put("reasonCodes", JSONArray(reasonCodes))
        .put("payload", payload.toJson())

    private fun CareActionPayload.toJson() = JSONObject()
        .putNullable("scheduledAtMs", scheduledAtMs)
        .putNullable("messageTemplateId", messageTemplateId)
        .putNullable("recipientId", recipientId)
        .putNullable("reportPeriodStartMs", reportPeriodStartMs)
        .putNullable("reportPeriodEndMs", reportPeriodEndMs)
        .put("parameters", JSONObject().apply {
            parameters.toSortedMap().forEach { (key, value) -> put(key, value) }
        })

    private fun JSONObject.toInput(): CareInputState {
        requireOnlyKeys(
            "profile", "canonicalClinicalReference", "recentAssessments", "trend", "adherence",
            "safetyEvents", "fallReports", "invalidAttemptNumerator", "invalidAttemptDenominator",
            "invalidAttemptRatio", "reassessmentDueAt", "nextPlannedSessionAt", "progressionEligible",
            "caregiverNotificationsConsented", "perceivedAt",
        )
        val profileJson = getJSONObject("profile")
        val clinicalJson = getJSONObject("canonicalClinicalReference")
        val trendJson = getJSONObject("trend")
        val adherenceJson = getJSONObject("adherence")
        profileJson.requireOnlyKeys("profileId", "birthYear", "sex", "sourceUpdatedAt")
        clinicalJson.requireOnlyKeys(
            "assessmentSessionId", "assessmentRevision", "steadiRuleVersion", "risk",
            "vulnerabilityRuleVersion", "vulnerabilityIds", "prescriptionPlanId",
            "prescriptionSchemaVersion", "professionalApprovalStatus", "professionalApprovalId",
        )
        trendJson.requireOnlyKeys("declining", "consecutiveDeclines")
        adherenceJson.requireOnlyKeys("completedSessionsByWeek", "targetSessionsPerWeek", "consecutiveLowWeeks")
        val result = CareInputState(
            profile = CareProfileSnapshot(
                profileId = profileJson.getString("profileId"),
                birthYear = profileJson.getInt("birthYear"),
                sex = profileJson.nullableString("sex"),
                sourceUpdatedAt = profileJson.getLong("sourceUpdatedAt"),
            ),
            canonicalClinicalReference = CareCanonicalClinicalReference(
                assessmentSessionId = clinicalJson.getString("assessmentSessionId"),
                assessmentRevision = clinicalJson.getLong("assessmentRevision"),
                steadiRuleVersion = clinicalJson.getString("steadiRuleVersion"),
                risk = enumValueOf<SteadiRisk>(clinicalJson.getString("risk")),
                vulnerabilityRuleVersion = clinicalJson.nullableString("vulnerabilityRuleVersion"),
                vulnerabilityIds = clinicalJson.getJSONArray("vulnerabilityIds").strings()
                    .mapTo(linkedSetOf()) { enumValueOf<VulnerabilityId>(it) },
                prescriptionPlanId = clinicalJson.nullableString("prescriptionPlanId"),
                prescriptionSchemaVersion = clinicalJson.nullableString("prescriptionSchemaVersion"),
                professionalApprovalStatus = enumValueOf<ApprovalStatus>(clinicalJson.getString("professionalApprovalStatus")),
                professionalApprovalId = clinicalJson.nullableString("professionalApprovalId"),
            ),
            recentAssessments = getJSONArray("recentAssessments").objects().map { value ->
                value.requireOnlyKeys(
                    "assessmentSessionId", "completedAt", "chairStandRepetitions", "tandemHoldSeconds", "valid",
                )
                CareAssessmentSummary(
                    assessmentSessionId = value.getString("assessmentSessionId"),
                    completedAt = value.getLong("completedAt"),
                    chairStandRepetitions = value.getInt("chairStandRepetitions"),
                    tandemHoldSeconds = value.getDouble("tandemHoldSeconds"),
                    valid = value.getBoolean("valid"),
                )
            },
            trend = CareTrendSnapshot(trendJson.getBoolean("declining"), trendJson.getInt("consecutiveDeclines")),
            adherence = CareAdherenceSnapshot(
                completedSessionsByWeek = adherenceJson.getJSONArray("completedSessionsByWeek").ints(),
                targetSessionsPerWeek = adherenceJson.getInt("targetSessionsPerWeek"),
                consecutiveLowWeeks = adherenceJson.getInt("consecutiveLowWeeks"),
            ),
            safetyEvents = getJSONArray("safetyEvents").objects().map { value ->
                value.requireOnlyKeys("eventId", "type", "occurredAt", "active")
                CareSafetyEventSnapshot(value.getString("eventId"), value.getString("type"), value.getLong("occurredAt"), value.getBoolean("active"))
            },
            fallReports = getJSONArray("fallReports").objects().map { value ->
                value.requireOnlyKeys("eventId", "occurredAt", "injurious", "unresolved")
                CareFallReportSnapshot(value.getString("eventId"), value.getLong("occurredAt"), value.getBoolean("injurious"), value.getBoolean("unresolved"))
            },
            invalidAttemptNumerator = getInt("invalidAttemptNumerator"),
            invalidAttemptDenominator = getInt("invalidAttemptDenominator"),
            invalidAttemptRatio = getDouble("invalidAttemptRatio"),
            reassessmentDueAt = getLong("reassessmentDueAt"),
            nextPlannedSessionAt = nullableLong("nextPlannedSessionAt"),
            progressionEligible = getBoolean("progressionEligible"),
            caregiverNotificationsConsented = getBoolean("caregiverNotificationsConsented"),
            perceivedAt = getLong("perceivedAt"),
        )
        require(result.profile.profileId.isNotBlank() && result.profile.birthYear >= 1900)
        require(result.profile.sex == null || result.profile.sex in setOf("MALE", "FEMALE"))
        require(result.recentAssessments.size <= CareAgentConfigV1.value.recentAssessmentLimit)
        require(result.recentAssessments.map { it.assessmentSessionId }.distinct().size == result.recentAssessments.size)
        require(result.recentAssessments.all {
            it.assessmentSessionId.isNotBlank() && it.completedAt >= 0L && it.chairStandRepetitions >= 0 &&
                it.tandemHoldSeconds.isFinite() && it.tandemHoldSeconds in 0.0..10.0
        })
        require(result.invalidAttemptNumerator >= 0 && result.invalidAttemptDenominator >= result.invalidAttemptNumerator)
        val expectedRatio = if (result.invalidAttemptDenominator == 0) 0.0 else
            result.invalidAttemptNumerator.toDouble() / result.invalidAttemptDenominator
        require(result.invalidAttemptRatio.isFinite() && kotlin.math.abs(result.invalidAttemptRatio - expectedRatio) < 1e-9)
        require(result.reassessmentDueAt >= 0L && result.nextPlannedSessionAt?.let { it >= 0L } != false && result.perceivedAt >= 0L)
        return result
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)

private fun JSONObject.requireOnlyKeys(vararg allowed: String) {
    val allowedSet = allowed.toSet()
    val actual = keys().asSequence().toSet()
    require(actual == allowedSet) {
        "Care state schema keys mismatch; missing=${allowedSet - actual}, unknown=${actual - allowedSet}"
    }
}

private fun JSONArray.strings() = buildList {
    for (index in 0 until length()) add(getString(index))
}

private fun JSONArray.ints() = buildList {
    for (index in 0 until length()) add(getInt(index))
}

private fun JSONArray.objects() = buildList {
    for (index in 0 until length()) add(getJSONObject(index))
}
