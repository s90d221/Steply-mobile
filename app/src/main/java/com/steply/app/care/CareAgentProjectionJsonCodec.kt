package com.steply.app.care

import org.json.JSONArray
import org.json.JSONObject

object CareAgentProjectionFactory {
    fun create(state: CareAgentState, decision: CareDecision?): CareAgentProjection {
        require(decision == null || decision.profileId == state.profileId)
        return createFromSummary(
            state,
            decision?.let { CareDecisionSummary(it.decisionId, it.selectedBranch, it.selectedActions, it.createdAt) },
        )
    }

    fun createFromSummary(state: CareAgentState, latestDecision: CareDecisionSummary?): CareAgentProjection {
        require(latestDecision == null || latestDecision.selectedActions.all { it.profileId == state.profileId })
        val clinical = state.input.canonicalClinicalReference
        val sessionPlan = clinical.prescriptionPlanId?.let { planId ->
            buildMap {
                put("prescriptionPlanId", planId)
                latestDecision?.let {
                    put("selectedBranch", it.selectedBranch.wireValue)
                    put("selectedActionTypes", it.selectedActions.joinToString(",") { action -> action.actionType.wireValue })
                }
            }
        }
        return CareAgentProjection(
            profileId = state.profileId,
            stateVersion = state.stateVersion,
            currentSessionPlan = sessionPlan,
            nextReassessmentAt = state.input.reassessmentDueAt,
            latestDecision = latestDecision,
            updatedAt = state.updatedAt,
        )
    }

    fun update(baseStateVersion: Long, projection: CareAgentProjection): CareAgentUpdate {
        require(projection.stateVersion > baseStateVersion)
        return CareAgentUpdate(
            messageId = "care-update:${projection.profileId}:${projection.stateVersion}",
            profileId = projection.profileId,
            baseStateVersion = baseStateVersion,
            stateVersion = projection.stateVersion,
            projection = projection,
        )
    }
}

object CareAgentProjectionJsonCodec {
    fun encodeProjection(value: CareAgentProjection): String = value.toJson().toString()

    fun decodeProjection(raw: String): CareAgentProjection = JSONObject(raw).toProjection()

    fun encodeUpdate(value: CareAgentUpdate): String = JSONObject()
        .put("type", value.type)
        .put("schemaVersion", value.schemaVersion)
        .put("messageId", value.messageId)
        .put("profileId", value.profileId)
        .put("baseStateVersion", value.baseStateVersion)
        .put("stateVersion", value.stateVersion)
        .put("projection", value.projection.toJson())
        .toString()

    fun decodeUpdate(raw: String): CareAgentUpdate {
        val json = JSONObject(raw)
        json.only("type", "schemaVersion", "messageId", "profileId", "baseStateVersion", "stateVersion", "projection")
        require(json.getString("type") == CARE_AGENT_UPDATED_TYPE)
        require(json.getString("schemaVersion") == CARE_AGENT_STATE_SCHEMA_VERSION)
        val projection = json.getJSONObject("projection").toProjection()
        val value = CareAgentUpdate(
            type = json.getString("type"),
            schemaVersion = json.getString("schemaVersion"),
            messageId = json.nonBlank("messageId"),
            profileId = json.nonBlank("profileId"),
            baseStateVersion = json.nonNegativeLong("baseStateVersion"),
            stateVersion = json.nonNegativeLong("stateVersion"),
            projection = projection,
        )
        require(value.stateVersion > value.baseStateVersion)
        require(projection.profileId == value.profileId)
        require(projection.stateVersion == value.stateVersion)
        return value
    }

    private fun CareAgentProjection.toJson() = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("profileId", profileId)
        .put("stateVersion", stateVersion)
        .put("currentSessionPlan", currentSessionPlan?.toJson() ?: JSONObject.NULL)
        .put("nextReassessmentAt", nextReassessmentAt ?: JSONObject.NULL)
        .put("latestDecision", latestDecision?.toJson() ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)

    private fun CareDecisionSummary.toJson() = JSONObject()
        .put("decisionId", decisionId)
        .put("selectedBranch", selectedBranch.wireValue)
        .put("selectedActions", JSONArray(selectedActions.map { it.toWireJson() }))
        .put("createdAt", createdAt)

    private fun CareActionCandidate.toWireJson() = JSONObject()
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
        .put("payload", JSONObject()
            .put("scheduledAtMs", payload.scheduledAtMs ?: JSONObject.NULL)
            .put("messageTemplateId", payload.messageTemplateId ?: JSONObject.NULL)
            .put("recipientId", payload.recipientId ?: JSONObject.NULL)
            .put("reportPeriodStartMs", payload.reportPeriodStartMs ?: JSONObject.NULL)
            .put("reportPeriodEndMs", payload.reportPeriodEndMs ?: JSONObject.NULL)
            .put("parameters", payload.parameters.toJson()))

    private fun JSONObject.toProjection(): CareAgentProjection {
        only("schemaVersion", "profileId", "stateVersion", "currentSessionPlan", "nextReassessmentAt", "latestDecision", "updatedAt")
        require(getString("schemaVersion") == CARE_AGENT_PROJECTION_SCHEMA_VERSION)
        val projection = CareAgentProjection(
            schemaVersion = getString("schemaVersion"),
            profileId = nonBlank("profileId"),
            stateVersion = nonNegativeLong("stateVersion"),
            currentSessionPlan = nullableObject("currentSessionPlan")?.stringMap(),
            nextReassessmentAt = nullableLong("nextReassessmentAt"),
            latestDecision = nullableObject("latestDecision")?.toDecisionSummary(),
            updatedAt = nonNegativeLong("updatedAt"),
        )
        projection.latestDecision?.selectedActions?.forEach { require(it.profileId == projection.profileId) }
        return projection
    }

    private fun JSONObject.toDecisionSummary(): CareDecisionSummary {
        only("decisionId", "selectedBranch", "selectedActions", "createdAt")
        return CareDecisionSummary(
            decisionId = nonBlank("decisionId"),
            selectedBranch = branch(getString("selectedBranch")),
            selectedActions = getJSONArray("selectedActions").objects().map { it.toAction() },
            createdAt = nonNegativeLong("createdAt"),
        )
    }

    private fun JSONObject.toAction(): CareActionCandidate {
        only(
            "schemaVersion", "actionId", "idempotencyKey", "eventId", "profileId", "branch",
            "actionType", "toolId", "target", "reasonCodes", "payload",
        )
        require(getString("schemaVersion") == CARE_AGENT_ACTION_SCHEMA_VERSION)
        val payloadJson = getJSONObject("payload")
        payloadJson.only(
            "scheduledAtMs", "messageTemplateId", "recipientId", "reportPeriodStartMs", "reportPeriodEndMs", "parameters",
        )
        return CareActionCandidate(
            actionId = nonBlank("actionId"),
            idempotencyKey = nonBlank("idempotencyKey"),
            eventId = nonBlank("eventId"),
            profileId = nonBlank("profileId"),
            branch = branch(getString("branch")),
            actionType = CareActionType.entries.first { it.wireValue == getString("actionType") },
            toolId = CareToolId.entries.first { it.wireValue == getString("toolId") },
            target = nonBlank("target"),
            reasonCodes = getJSONArray("reasonCodes").strings().also { require(it.distinct().size == it.size) },
            payload = CareActionPayload(
                scheduledAtMs = payloadJson.nullableLong("scheduledAtMs"),
                messageTemplateId = payloadJson.nullableString("messageTemplateId"),
                recipientId = payloadJson.nullableString("recipientId"),
                reportPeriodStartMs = payloadJson.nullableLong("reportPeriodStartMs"),
                reportPeriodEndMs = payloadJson.nullableLong("reportPeriodEndMs"),
                parameters = payloadJson.getJSONObject("parameters").stringMap(),
            ),
            schemaVersion = getString("schemaVersion"),
        )
    }

    private fun branch(value: String) = CareDecisionBranch.entries.first { it.wireValue == value }
}

private fun Map<String, String>.toJson() = JSONObject().apply {
    toSortedMap().forEach { (key, value) -> put(key, value) }
}

private fun JSONObject.only(vararg allowed: String) {
    val expected = allowed.toSet()
    val actual = keys().asSequence().toSet()
    require(actual == expected) { "Care projection keys mismatch; missing=${expected - actual}, unknown=${actual - expected}" }
}

private fun JSONObject.nonBlank(key: String) = getString(key).also { require(it.isNotBlank()) }
private fun JSONObject.nonNegativeLong(key: String) = getLong(key).also { require(it >= 0L) }
private fun JSONObject.nullableLong(key: String) = if (isNull(key)) null else nonNegativeLong(key)
private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else nonBlank(key)
private fun JSONObject.nullableObject(key: String) = if (isNull(key)) null else getJSONObject(key)
private fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { key -> nonBlank(key) }
private fun JSONArray.strings() = buildList { for (index in 0 until length()) add(getString(index).also { require(it.isNotBlank()) }) }
private fun JSONArray.objects() = buildList { for (index in 0 until length()) add(getJSONObject(index)) }
