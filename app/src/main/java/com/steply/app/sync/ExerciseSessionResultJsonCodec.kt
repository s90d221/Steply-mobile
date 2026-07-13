package com.steply.app.sync

import com.steply.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

object ExerciseSessionResultJsonCodec {
    fun encode(result: ExerciseSessionResult): String = encodeObject(result).toString()

    fun encodeObject(result: ExerciseSessionResult): JSONObject {
        validate(result)
        return JSONObject()
            .put("schemaVersion", result.schemaVersion)
            .put("resultId", result.resultId)
            .put("planId", result.planId)
            .put("exerciseSessionId", result.exerciseSessionId)
            .put("exerciseId", result.exerciseId.name)
            .put("variantId", result.variantId)
            .put("level", result.level.name)
            .put("source", result.source.name)
            .put("startedAt", result.startedAt)
            .put("completedAt", result.completedAt)
            .put("prescribedDosage", result.prescribedDosage.toJson())
            .put("completedDosage", result.completedDosage.toJson())
            .put("formAccurate", result.formAccurate)
            .put("lowerBodyRecoveryWithoutGripping", result.lowerBodyRecoveryWithoutGripping ?: JSONObject.NULL)
            .put("supportUsed", result.supportUsed)
            .put("safetyEvents", JSONArray(result.safetyEvents))
            .put("cameraVerification", result.cameraVerification.name)
    }

    fun decode(rawJson: String): ExerciseSessionResult {
        val json = runCatching { JSONObject(rawJson) }
            .getOrElse { throw AssessmentSessionContractException("Exercise result is not valid JSON") }
        return decodeObject(json)
    }

    fun decodeObject(json: JSONObject): ExerciseSessionResult {
        json.only(
            "schemaVersion", "resultId", "planId", "exerciseSessionId", "exerciseId", "variantId", "level", "source",
            "startedAt", "completedAt", "prescribedDosage", "completedDosage", "formAccurate",
            "lowerBodyRecoveryWithoutGripping", "supportUsed", "safetyEvents", "cameraVerification",
        )
        val result = ExerciseSessionResult(
            schemaVersion = json.string("schemaVersion"),
            resultId = json.string("resultId"),
            planId = json.string("planId"),
            exerciseSessionId = json.string("exerciseSessionId"),
            exerciseId = json.enum("exerciseId"),
            variantId = json.string("variantId"),
            level = json.enum("level"),
            source = json.enum("source"),
            startedAt = json.long("startedAt"),
            completedAt = json.long("completedAt"),
            prescribedDosage = json.objectValue("prescribedDosage").toDosage(),
            completedDosage = json.objectValue("completedDosage").toDosage(),
            formAccurate = json.boolean("formAccurate"),
            lowerBodyRecoveryWithoutGripping = json.nullableBoolean("lowerBodyRecoveryWithoutGripping"),
            supportUsed = json.boolean("supportUsed"),
            safetyEvents = json.array("safetyEvents").strings(),
            cameraVerification = json.enum("cameraVerification"),
        )
        validate(result)
        return result
    }

    fun validate(result: ExerciseSessionResult) {
        if (result.schemaVersion != EXERCISE_SESSION_RESULT_SCHEMA_VERSION) fail("Exercise result schemaVersion is invalid")
        if (listOf(result.resultId, result.planId, result.exerciseSessionId, result.variantId).any { it.isBlank() }) fail("Exercise result IDs must not be blank")
        if (result.startedAt < 0L || result.completedAt < result.startedAt) fail("Exercise result timestamps are invalid")
        validateDosage(result.prescribedDosage, requireTarget = true)
        validateDosage(result.completedDosage, requireTarget = false)
        if (result.safetyEvents.any { it.isBlank() } || result.safetyEvents.distinct().size != result.safetyEvents.size) fail("safetyEvents must be unique non-blank values")
        if (result.source == ExerciseResultSource.LIVE_POSE && result.cameraVerification == CameraVerification.MANUAL_ONLY) {
            fail("MANUAL_ONLY exercise results cannot claim LIVE_POSE source")
        }
        if (result.exerciseId.expectedCategory() != ExerciseCategory.BALANCE && result.lowerBodyRecoveryWithoutGripping != null) {
            fail("lowerBodyRecoveryWithoutGripping is reserved for balance exercises")
        }
    }
}

private fun ExerciseDosage.toJson() = JSONObject()
    .put("repetitions", repetitions ?: JSONObject.NULL)
    .put("sets", sets ?: JSONObject.NULL)
    .put("repetitionsPerSide", repetitionsPerSide ?: JSONObject.NULL)
    .put("steps", steps ?: JSONObject.NULL)
    .put("holdSeconds", holdSeconds ?: JSONObject.NULL)

private fun JSONObject.toDosage(): ExerciseDosage {
    only("repetitions", "sets", "repetitionsPerSide", "steps", "holdSeconds")
    return ExerciseDosage(nullableInt("repetitions"), nullableInt("sets"), nullableInt("repetitionsPerSide"), nullableInt("steps"), nullableInt("holdSeconds"))
}

private fun validateDosage(value: ExerciseDosage, requireTarget: Boolean) {
    val values = listOf(value.repetitions, value.sets, value.repetitionsPerSide, value.steps, value.holdSeconds)
    values.filterNotNull().forEach { if (it < 0 || (requireTarget && it == 0)) fail("Exercise dosage is invalid") }
    if (requireTarget && listOf(value.repetitions, value.repetitionsPerSide, value.steps, value.holdSeconds).all { it == null }) fail("Prescribed dosage requires a target")
}

private fun JSONObject.only(vararg allowed: String) {
    val allowedSet = allowed.toSet()
    for (key in keys()) if (key !in allowedSet) fail("Unsupported exercise result field: $key")
    allowed.forEach { if (!has(it)) fail("Missing exercise result field: $it") }
}
private fun JSONObject.string(name: String): String = opt(name).let { if (it !is String || it.isBlank()) fail("$name must be non-blank") else it }
private fun JSONObject.boolean(name: String): Boolean = opt(name).let { if (it !is Boolean) fail("$name must be boolean") else it }
private fun JSONObject.nullableBoolean(name: String): Boolean? = if (isNull(name)) null else boolean(name)
private fun JSONObject.long(name: String): Long = opt(name).let { if (it !is Number || it.toDouble() != it.toLong().toDouble()) fail("$name must be an integer") else it.toLong() }
private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else long(name).let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$name must fit Int") else it.toInt() }
private fun JSONObject.objectValue(name: String): JSONObject = optJSONObject(name) ?: fail("$name must be an object")
private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: fail("$name must be an array")
private inline fun <reified T : Enum<T>> JSONObject.enum(name: String): T {
    val raw = string(name)
    return enumValues<T>().firstOrNull { it.name == raw } ?: fail("Unsupported $name: $raw")
}
private fun JSONArray.strings(): List<String> = List(length()) { index ->
    val value = opt(index)
    if (value !is String || value.isBlank()) fail("String array item must be non-blank")
    value
}
private fun fail(message: String): Nothing = throw AssessmentSessionContractException(message)
