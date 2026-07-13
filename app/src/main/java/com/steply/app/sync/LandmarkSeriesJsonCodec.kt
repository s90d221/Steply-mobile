package com.steply.app.sync

import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.AssessmentType
import org.json.JSONArray
import org.json.JSONObject

const val LANDMARK_SERIES_SCHEMA_VERSION = "landmark_series.v1"
const val LANDMARK_SERIES_FINALIZED_TYPE = "landmark-series.finalized"
const val LANDMARK_SERIES_ACK_TYPE = "landmark-series.ack"

data class PoseLandmarkPoint(
    val index: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double,
)

data class LandmarkSample(
    val sequence: Long,
    val timestampMs: Double,
    val normalizedLandmarks: List<PoseLandmarkPoint>,
    val worldLandmarks: List<PoseLandmarkPoint>,
)

data class LandmarkSeries(
    val schemaVersion: String,
    val seriesId: String,
    val profileId: String,
    val assessmentSessionId: String,
    val attemptId: String,
    val analysisSessionId: String,
    val resultId: String,
    val assessmentType: AssessmentType,
    val status: AssessmentAttemptStatus,
    val targetFps: Int,
    val startedAt: Long,
    val completedAt: Long,
    val samples: List<LandmarkSample>,
)

data class LandmarkSeriesEnvelope(
    val messageId: String,
    val series: LandmarkSeries,
)

object LandmarkSeriesJsonCodec {
    fun decodeFinalized(raw: String): LandmarkSeriesEnvelope = decodeFinalized(JSONObject(raw))

    fun decodeFinalized(json: JSONObject): LandmarkSeriesEnvelope {
        json.only(
            "type", "schemaVersion", "messageId", "profileId", "assessmentSessionId", "attemptId",
            "resultId", "series",
        )
        json.exact("type", LANDMARK_SERIES_FINALIZED_TYPE)
        json.exact("schemaVersion", LANDMARK_SERIES_SCHEMA_VERSION)
        val series = decodeSeries(json.obj("series"))
        require(json.text("profileId") == series.profileId)
        require(json.text("assessmentSessionId") == series.assessmentSessionId)
        require(json.text("attemptId") == series.attemptId)
        require(json.text("resultId") == series.resultId)
        return LandmarkSeriesEnvelope(json.text("messageId"), series)
    }

    fun encodeAck(envelope: LandmarkSeriesEnvelope, storedAt: Long): String {
        require(storedAt >= 0L)
        return JSONObject()
            .put("type", LANDMARK_SERIES_ACK_TYPE)
            .put("schemaVersion", LANDMARK_SERIES_SCHEMA_VERSION)
            .put("messageId", envelope.messageId)
            .put("profileId", envelope.series.profileId)
            .put("assessmentSessionId", envelope.series.assessmentSessionId)
            .put("attemptId", envelope.series.attemptId)
            .put("seriesId", envelope.series.seriesId)
            .put("storedAt", storedAt)
            .toString()
    }

    fun encodeSamples(samples: List<LandmarkSample>): String = JSONArray().also { array ->
        samples.forEach { array.put(encodeSample(it)) }
    }.toString()

    private fun decodeSeries(json: JSONObject): LandmarkSeries {
        json.only(
            "schemaVersion", "seriesId", "profileId", "assessmentSessionId", "attemptId", "analysisSessionId",
            "resultId", "assessmentType", "status", "targetFps", "startedAt", "completedAt", "samples",
        )
        json.exact("schemaVersion", LANDMARK_SERIES_SCHEMA_VERSION)
        val samples = json.array("samples").objects(::decodeSample)
        require(samples.size <= Stage5DataConfig.LANDMARK_MAX_SAMPLES)
        require(samples.zipWithNext().all { (a, b) -> b.sequence > a.sequence && b.timestampMs > a.timestampMs }) {
            "Landmark samples must be ordered by sequence and timestamp"
        }
        val value = LandmarkSeries(
            schemaVersion = LANDMARK_SERIES_SCHEMA_VERSION,
            seriesId = json.text("seriesId"),
            profileId = json.text("profileId"),
            assessmentSessionId = json.text("assessmentSessionId"),
            attemptId = json.text("attemptId"),
            analysisSessionId = json.text("analysisSessionId"),
            resultId = json.text("resultId"),
            assessmentType = json.enum("assessmentType"),
            status = json.enum("status"),
            targetFps = json.int("targetFps"),
            startedAt = json.long("startedAt"),
            completedAt = json.long("completedAt"),
            samples = samples,
        )
        require(value.targetFps == Stage5DataConfig.LANDMARK_TARGET_FPS && value.startedAt >= 0L && value.completedAt >= value.startedAt)
        require(value.status !in setOf(AssessmentAttemptStatus.IN_PROGRESS, AssessmentAttemptStatus.PAUSED))
        return value
    }

    private fun decodeSample(json: JSONObject): LandmarkSample {
        json.only("sequence", "timestampMs", "normalizedLandmarks", "worldLandmarks")
        val normalized = json.array("normalizedLandmarks").landmarks()
        val world = json.array("worldLandmarks").landmarks()
        return LandmarkSample(json.long("sequence"), json.number("timestampMs"), normalized, world).also {
            require(it.sequence >= 0L && it.timestampMs >= 0.0)
        }
    }

    private fun JSONArray.landmarks(): List<PoseLandmarkPoint> {
        require(length() == 33) { "Landmark arrays must contain exactly 33 ordered points" }
        return objects { json ->
            json.only("index", "x", "y", "z", "visibility")
            PoseLandmarkPoint(
                json.int("index"), json.number("x"), json.number("y"), json.number("z"), json.number("visibility"),
            ).also { require(it.visibility in 0.0..1.0) }
        }.also { points -> require(points.map { it.index } == (0..32).toList()) }
    }

    private fun encodeSample(value: LandmarkSample) = JSONObject()
        .put("sequence", value.sequence)
        .put("timestampMs", value.timestampMs)
        .put("normalizedLandmarks", JSONArray().also { a -> value.normalizedLandmarks.forEach { a.put(encodePoint(it)) } })
        .put("worldLandmarks", JSONArray().also { a -> value.worldLandmarks.forEach { a.put(encodePoint(it)) } })

    private fun encodePoint(value: PoseLandmarkPoint) = JSONObject()
        .put("index", value.index).put("x", value.x).put("y", value.y).put("z", value.z).put("visibility", value.visibility)
}

private fun JSONObject.only(vararg allowedKeys: String) {
    val allowed = allowedKeys.toSet()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        require(key in allowed) { "Unsupported landmark field: $key" }
    }
    allowedKeys.forEach { require(has(it)) { "Missing landmark field: $it" } }
}
private fun JSONObject.text(name: String): String = (opt(name) as? String)?.takeIf { it.isNotBlank() }
    ?: error("$name must be non-blank")
private fun JSONObject.exact(name: String, expected: String) = require(text(name) == expected) { "$name must be $expected" }
private fun JSONObject.obj(name: String): JSONObject = optJSONObject(name) ?: error("$name must be object")
private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: error("$name must be array")
private fun JSONObject.number(name: String): Double = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    ?: error("$name must be finite number")
private fun JSONObject.long(name: String): Long = number(name).let { value ->
    val long = value.toLong(); require(value == long.toDouble()) { "$name must be integer" }; long
}
private fun JSONObject.int(name: String): Int = long(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private inline fun <reified T : Enum<T>> JSONObject.enum(name: String): T = enumValues<T>().firstOrNull { it.name == text(name) }
    ?: error("Unsupported $name")
private fun <T> JSONArray.objects(mapper: (JSONObject) -> T): List<T> = List(length()) { index ->
    mapper(optJSONObject(index) ?: error("Array item must be object"))
}
