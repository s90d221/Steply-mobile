package com.steply.app.sync

import com.steply.app.care.CareAgentConfigV1
import com.steply.app.care.CareAgentState
import com.steply.app.data.repository.CareDecisionLogSnapshot
import com.steply.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

const val STEPLY_DATA_CONTRACT_SCHEMA_VERSION = "steply_data_contract.v1"

data class SteplyDataProfile(
    val id: String,
    val displayName: String,
    val birthYear: Int,
    val sex: AssessmentSex?,
)

data class SteplyRecentAssessment(
    val assessmentSessionId: String,
    val completedAt: Long,
    val risk: SteadiRisk,
    val vulnerabilityIds: List<VulnerabilityId>,
    val valid: Boolean = true,
    val chairStandRepetitions: Int,
    val balanceSecondsByStage: Map<BalanceStage, Double>,
)

data class SteplySafetyEvent(val eventId: String, val type: String, val occurredAt: Long)
data class SteplyFallReport(val eventId: String, val occurredAt: Long, val injurious: Boolean, val unresolved: Boolean)
data class SteplyInvalidAttempts(val numerator: Int, val denominator: Int, val ratio: Double)
data class SteplyAdherence(val completedSessions: Int, val targetSessions: Int)
enum class SteplyReportRecommendationStatus { MAINTAIN, PROGRESS, REASSESS, PROFESSIONAL_REVIEW }
data class SteplyReportRecommendation(
    val status: SteplyReportRecommendationStatus,
    val requiresProfessionalReview: Boolean,
    val progressionEligible: Boolean,
)
data class SteplyAgentRationale(
    val actionType: String,
    val reasonCodes: List<String>,
    val executionStatus: String,
    val occurredAt: Long,
)
data class SteplyWeeklyReportSnapshot(
    val periodStart: Long,
    val periodEnd: Long,
    val generatedAt: Long,
    val latestRiskLevel: SteadiRisk?,
    val previousRiskLevel: SteadiRisk?,
    val riskChanged: Boolean,
    val vulnerabilityIds: List<VulnerabilityId>,
    val adherence: SteplyAdherence,
    val invalidAttempts: SteplyInvalidAttempts,
    val safetyEvents: List<SteplySafetyEvent>,
    val fallReports: List<SteplyFallReport>,
    val recommendation: SteplyReportRecommendation,
    val agentRationale: List<SteplyAgentRationale>,
    val recentAssessments: List<SteplyRecentAssessment>,
)
data class SteplyDataContract(
    val schemaVersion: String = STEPLY_DATA_CONTRACT_SCHEMA_VERSION,
    val profile: SteplyDataProfile,
    val recentAssessments: List<SteplyRecentAssessment>,
    val generatedAt: Long,
)

/** Mobile-local report input. This type is never encoded into the PC connect payload. */
data class SteplyLocalReportData(
    val profile: SteplyDataProfile,
    val recentAssessments: List<SteplyRecentAssessment>,
    val weeklyReport: SteplyWeeklyReportSnapshot,
    val generatedAt: Long,
)

object SteplyDataContractBuilder {
    fun build(
        profile: UserProfile,
        sessions: List<AssessmentSession>,
        generatedAt: Long = System.currentTimeMillis(),
    ): SteplyDataContract {
        val recent = sessions.asSequence()
            .filter { it.profileId == profile.id && it.hasScoredAggregate() }
            .sortedBy { it.completedAt }
            .mapNotNull(::toRecentAssessment)
            .toList()
            .takeLast(Stage5DataConfig.RECENT_ASSESSMENT_LIMIT)
        return SteplyDataContract(
            profile = SteplyDataProfile(profile.id, profile.displayName, profile.birthYear, profile.gender.toAssessmentSex()),
            recentAssessments = recent,
            generatedAt = generatedAt,
        ).also(SteplyDataContractJsonCodec::validate)
    }

    fun buildLocalReport(
        profile: UserProfile,
        sessions: List<AssessmentSession>,
        workouts: List<com.steply.app.domain.model.WorkoutProgress>,
        careState: CareAgentState?,
        decisions: List<CareDecisionLogSnapshot>,
        generatedAt: Long = System.currentTimeMillis(),
    ): SteplyLocalReportData {
        require(careState == null || careState.profileId == profile.id)
        require(workouts.all { it.profileId == profile.id })
        require(decisions.all { it.profileId == profile.id })
        val config = CareAgentConfigV1.value
        val profileSessions = sessions.filter { it.profileId == profile.id }
        val scored = profileSessions.filter { it.hasScoredAggregate() }
            .sortedBy { it.completedAt }
        val connectionData = build(profile, profileSessions, generatedAt)
        val recent = connectionData.recentAssessments
        val terminalAttempts = profileSessions.flatMap { session ->
            session.functionalTests.chairStand30s.attempts + session.functionalTests.fourStageBalance.attempts
        }.filter { it.status !in setOf(AssessmentAttemptStatus.IN_PROGRESS, AssessmentAttemptStatus.PAUSED) }
        val invalidCount = terminalAttempts.count { it.status == AssessmentAttemptStatus.INVALID || it.result?.quality?.excludeFromTrends == true }
        val invalid = SteplyInvalidAttempts(
            invalidCount,
            terminalAttempts.size,
            if (terminalAttempts.isEmpty()) 0.0 else invalidCount.toDouble() / terminalAttempts.size,
        )
        val weekStart = generatedAt - config.weeklyReportIntervalMs
        val completedWorkouts = workouts.count { it.status == WorkoutStatus.COMPLETED && it.completedAt in weekStart..generatedAt }
        val latest = scored.lastOrNull()
        val safety = buildSafetyEvents(profileSessions, careState).filter { it.occurredAt in weekStart..generatedAt }
        val falls = careState?.input?.fallReports.orEmpty().filter { it.occurredAt in weekStart..generatedAt }.map {
            SteplyFallReport(it.eventId, it.occurredAt, it.injurious, it.unresolved)
        }
        val rationales = buildRationales(decisions).filter { it.occurredAt in weekStart..generatedAt }
        val requiresReview = latest?.exercisePrescription?.plan?.requiresProfessionalReview == true ||
            latest?.exercisePrescription?.status == PrescriptionStatus.PENDING_PROFESSIONAL_REVIEW ||
            latest?.steadi?.risk == SteadiRisk.HIGH
        val progression = careState?.input?.progressionEligible == true
        val recommendationStatus = when {
            requiresReview -> SteplyReportRecommendationStatus.PROFESSIONAL_REVIEW
            progression -> SteplyReportRecommendationStatus.PROGRESS
            careState?.input?.trend?.declining == true || careState?.input?.fallReports?.any { it.unresolved } == true ||
                careState?.input?.safetyEvents?.any { it.active } == true -> SteplyReportRecommendationStatus.REASSESS
            else -> SteplyReportRecommendationStatus.MAINTAIN
        }
        val latestRisk = scored.lastOrNull { (it.completedAt ?: Long.MAX_VALUE) <= generatedAt }?.steadi?.risk
        val previousRisk = scored.lastOrNull { (it.completedAt ?: Long.MAX_VALUE) < weekStart }?.steadi?.risk
        val weekly = SteplyWeeklyReportSnapshot(
            periodStart = weekStart,
            periodEnd = generatedAt,
            generatedAt = generatedAt,
            latestRiskLevel = latestRisk,
            previousRiskLevel = previousRisk,
            riskChanged = previousRisk != null && latestRisk != previousRisk,
            vulnerabilityIds = recent.lastOrNull()?.vulnerabilityIds.orEmpty(),
            adherence = SteplyAdherence(completedWorkouts, config.adherenceTargetSessionsPerWeek),
            invalidAttempts = invalid,
            safetyEvents = safety,
            fallReports = falls,
            recommendation = SteplyReportRecommendation(recommendationStatus, requiresReview, progression),
            agentRationale = rationales,
            recentAssessments = recent,
        )
        return SteplyLocalReportData(
            profile = connectionData.profile,
            recentAssessments = recent,
            weeklyReport = weekly,
            generatedAt = generatedAt,
        ).also(::validateLocalReport)
    }

    private fun validateLocalReport(value: SteplyLocalReportData) {
        SteplyDataContractJsonCodec.validate(
            SteplyDataContract(
                profile = value.profile,
                recentAssessments = value.recentAssessments,
                generatedAt = value.generatedAt,
            ),
        )
        val report = value.weeklyReport
        require(report.periodStart >= 0 && report.periodEnd >= report.periodStart && report.generatedAt == value.generatedAt)
        require(report.recentAssessments == value.recentAssessments)
        require(report.adherence.completedSessions >= 0 && report.adherence.targetSessions >= 1)
        require(report.invalidAttempts.numerator in 0..report.invalidAttempts.denominator)
        val expected = if (report.invalidAttempts.denominator == 0) 0.0 else
            report.invalidAttempts.numerator.toDouble() / report.invalidAttempts.denominator
        require(kotlin.math.abs(report.invalidAttempts.ratio - expected) <= 1e-9)
        require(report.vulnerabilityIds.distinct().size == report.vulnerabilityIds.size)
        val statuses = setOf("PLANNED", "RUNNING", "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "SKIPPED_DUPLICATE")
        require(report.agentRationale.all {
            it.actionType.isNotBlank() && it.reasonCodes.distinct().size == it.reasonCodes.size &&
                it.executionStatus in statuses && it.occurredAt >= 0
        })
    }

    private fun toRecentAssessment(session: AssessmentSession): SteplyRecentAssessment? {
        val chair = session.functionalTests.chairStand30s.acceptedResult ?: return null
        val balance = session.functionalTests.fourStageBalance.acceptedResult ?: return null
        if (chair.quality?.excludeFromTrends == true || balance.quality?.excludeFromTrends == true) return null
        val chairRepetitions = chair.chairStand?.cdcScoredRepetitions ?: return null
        val stages = balance.balance?.stages?.associate { it.stage to it.holdSeconds } ?: return null
        if (BalanceStage.entries.any { it !in stages }) return null
        return SteplyRecentAssessment(
            session.assessmentSessionId,
            session.completedAt ?: return null,
            session.steadi.risk,
            session.vulnerabilityAssessment?.activeIds.orEmpty(),
            true,
            chairRepetitions,
            BalanceStage.entries.associateWith { requireNotNull(stages[it]) },
        )
    }

    private fun buildSafetyEvents(sessions: List<AssessmentSession>, state: CareAgentState?): List<SteplySafetyEvent> {
        val fromState = state?.input?.safetyEvents.orEmpty().map { SteplySafetyEvent(it.eventId, it.type, it.occurredAt) }
        val fromResults = sessions.flatMap { session ->
            val attempts = session.functionalTests.chairStand30s.attempts + session.functionalTests.fourStageBalance.attempts
            attempts.flatMap { attempt ->
                val result = attempt.result ?: return@flatMap emptyList()
                buildList {
                    result.chairStand?.armUse?.outcome?.takeIf { it != ArmUseOutcome.NOT_DETECTED }?.let {
                        add(SteplySafetyEvent("${result.resultId}:ARM_USE:${it.name}", "ARM_USE_${it.name}", result.completedAt))
                    }
                    result.balance?.stages.orEmpty().forEach { stage ->
                        stage.failureCode?.let { add(SteplySafetyEvent("${result.resultId}:${stage.stage}:${it.name}", it.name, result.completedAt)) }
                    }
                }
            } + session.exercisePrescription.sessionResults.flatMap { result ->
                result.safetyEvents.map { type -> SteplySafetyEvent("${result.resultId}:$type", type, result.completedAt) }
            }
        }
        return (fromState + fromResults).distinctBy { it.eventId }.sortedBy { it.occurredAt }
    }

    private fun buildRationales(decisions: List<CareDecisionLogSnapshot>): List<SteplyAgentRationale> = decisions.flatMap { decision ->
        val candidates = JSONArray(decision.candidateActionsJson).objects().associateBy { it.getString("actionId") }
        val selected = JSONArray(decision.candidateDecisionsJson).objects()
            .filter { it.getString("disposition") == "SELECTED" }.map { it.getString("actionId") }
        val executions = JSONArray(decision.executionResultsJson).objects().associateBy { it.getString("actionId") }
        selected.mapNotNull { id ->
            val action = candidates[id] ?: return@mapNotNull null
            SteplyAgentRationale(
                action.getString("actionType"),
                action.getJSONArray("reasonCodes").strings(),
                executions[id]?.getString("status") ?: "PLANNED",
                decision.completedAt ?: decision.createdAt,
            )
        }
    }
}

object SteplyDataContractJsonCodec {
    fun encode(value: SteplyDataContract): String = encodeObject(value).toString()
    fun encodeObject(value: SteplyDataContract): JSONObject {
        validate(value)
        return JSONObject()
            .put("schemaVersion", value.schemaVersion)
            .put("profile", value.profile.json())
            .put("recentAssessments", JSONArray().also { a -> value.recentAssessments.forEach { a.put(it.json()) } })
            .put("generatedAt", value.generatedAt)
    }

    fun decode(raw: String): SteplyDataContract = decode(JSONObject(raw))
    fun decode(json: JSONObject): SteplyDataContract {
        json.dataOnly("schemaVersion", "profile", "recentAssessments", "generatedAt")
        val profileJson = json.dataObject("profile").also { it.dataOnly("id", "displayName", "birthYear", "sex") }
        val recent = json.dataArray("recentAssessments").dataObjects(::decodeAssessment)
        return SteplyDataContract(
            json.dataText("schemaVersion"),
            SteplyDataProfile(
                profileJson.dataText("id"), profileJson.dataText("displayName"), profileJson.dataInt("birthYear"),
                profileJson.dataNullableEnum<AssessmentSex>("sex"),
            ),
            recent,
            json.dataLong("generatedAt"),
        ).also(::validate)
    }

    fun validate(value: SteplyDataContract) {
        require(value.schemaVersion == STEPLY_DATA_CONTRACT_SCHEMA_VERSION)
        require(value.profile.id.isNotBlank() && value.profile.displayName.isNotBlank() && value.profile.birthYear >= 1900)
        require(value.generatedAt >= 0)
        require(value.recentAssessments.size <= Stage5DataConfig.RECENT_ASSESSMENT_LIMIT && value.recentAssessments.distinctBy { it.assessmentSessionId }.size == value.recentAssessments.size)
        require(value.recentAssessments.zipWithNext().all { (a, b) -> a.completedAt <= b.completedAt })
        value.recentAssessments.forEach { assessment ->
            require(assessment.valid && assessment.risk in setOf(SteadiRisk.LOW, SteadiRisk.MODERATE, SteadiRisk.HIGH))
            require(assessment.chairStandRepetitions >= 0 && assessment.vulnerabilityIds.distinct().size == assessment.vulnerabilityIds.size)
            require(assessment.balanceSecondsByStage.keys == BalanceStage.entries.toSet())
            require(assessment.balanceSecondsByStage.values.all { it.isFinite() && it in 0.0..10.0 })
        }
    }

    private fun decodeAssessment(json: JSONObject): SteplyRecentAssessment {
        json.dataOnly("assessmentSessionId", "completedAt", "risk", "vulnerabilityIds", "valid", "chairStandRepetitions", "balanceSecondsByStage")
        val balance = json.dataObject("balanceSecondsByStage").also { it.dataOnly(*BalanceStage.entries.map { stage -> stage.name }.toTypedArray()) }
        return SteplyRecentAssessment(
            json.dataText("assessmentSessionId"), json.dataLong("completedAt"), json.dataEnum("risk"),
            json.dataArray("vulnerabilityIds").dataEnums(), json.dataBoolean("valid"), json.dataInt("chairStandRepetitions"),
            BalanceStage.entries.associateWith { balance.dataDouble(it.name) },
        )
    }
}

private fun SteplyDataProfile.json() = JSONObject().put("id", id).put("displayName", displayName).put("birthYear", birthYear)
    .put("sex", sex?.name ?: JSONObject.NULL)
private fun SteplyRecentAssessment.json() = JSONObject().put("assessmentSessionId", assessmentSessionId).put("completedAt", completedAt)
    .put("risk", risk.name).put("vulnerabilityIds", JSONArray(vulnerabilityIds.map { it.name })).put("valid", valid)
    .put("chairStandRepetitions", chairStandRepetitions)
    .put("balanceSecondsByStage", JSONObject().also { o -> BalanceStage.entries.forEach { o.put(it.name, balanceSecondsByStage.getValue(it)) } })
private fun String?.toAssessmentSex(): AssessmentSex? = when (this?.trim()?.uppercase()) {
    "MALE", "M", "MAN" -> AssessmentSex.MALE
    "FEMALE", "F", "WOMAN" -> AssessmentSex.FEMALE
    else -> null
}
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONObject.dataOnly(vararg allowedKeys: String) {
    val allowed = allowedKeys.toSet(); val iterator = keys()
    while (iterator.hasNext()) require(iterator.next() in allowed)
    allowedKeys.forEach { require(has(it)) }
}
private fun JSONObject.dataText(name: String): String = (opt(name) as? String)?.takeIf { it.isNotBlank() } ?: error("$name must be text")
private fun JSONObject.dataLong(name: String): Long = (opt(name) as? Number)?.let { number ->
    val value = number.toDouble(); val long = number.toLong(); require(value.isFinite() && value == long.toDouble()); long
} ?: error("$name must be integer")
private fun JSONObject.dataInt(name: String): Int = dataLong(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun JSONObject.dataDouble(name: String): Double = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: error("$name must be number")
private fun JSONObject.dataBoolean(name: String): Boolean = opt(name) as? Boolean ?: error("$name must be boolean")
private fun JSONObject.dataObject(name: String): JSONObject = optJSONObject(name) ?: error("$name must be object")
private fun JSONObject.dataArray(name: String): JSONArray = optJSONArray(name) ?: error("$name must be array")
private inline fun <reified T : Enum<T>> JSONObject.dataEnum(name: String): T = enumValues<T>().firstOrNull { it.name == dataText(name) } ?: error("Unsupported $name")
private inline fun <reified T : Enum<T>> JSONObject.dataNullableEnum(name: String): T? = if (isNull(name)) null else dataEnum<T>(name)
private inline fun <reified T : Enum<T>> JSONArray.dataEnums(): List<T> = List(length()) { index ->
    enumValues<T>().firstOrNull { it.name == getString(index) } ?: error("Unsupported enum")
}
private fun <T> JSONArray.dataObjects(mapper: (JSONObject) -> T): List<T> = List(length()) { mapper(getJSONObject(it)) }
