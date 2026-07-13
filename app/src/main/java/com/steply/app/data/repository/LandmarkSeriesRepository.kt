package com.steply.app.data.repository

import com.steply.app.data.local.dao.AssessmentSessionDao
import com.steply.app.data.local.dao.LandmarkSeriesDao
import com.steply.app.data.local.entities.LandmarkSeriesEntity
import com.steply.app.sync.AssessmentSessionJsonCodec
import com.steply.app.sync.LandmarkSeriesEnvelope
import com.steply.app.sync.LandmarkSeriesJsonCodec

enum class LandmarkPersistResult { APPLIED, DUPLICATE }
data class LandmarkPersistReceipt(
    val envelope: LandmarkSeriesEnvelope,
    val result: LandmarkPersistResult,
    val storedAt: Long,
)

class LandmarkSeriesRepository(
    private val dao: LandmarkSeriesDao,
    private val assessmentSessionDao: AssessmentSessionDao,
) {
    suspend fun persistFinalized(rawJson: String, storedAt: Long = System.currentTimeMillis()): LandmarkPersistReceipt {
        val envelope = LandmarkSeriesJsonCodec.decodeFinalized(rawJson)
        val series = envelope.series
        val assessmentRow = requireNotNull(assessmentSessionDao.getById(series.assessmentSessionId)) {
            "Landmark series references an unknown assessment"
        }
        require(assessmentRow.profileId == series.profileId)
        val assessment = AssessmentSessionJsonCodec.decode(assessmentRow.envelopeJson).session
        val slots = listOf(assessment.functionalTests.chairStand30s, assessment.functionalTests.fourStageBalance)
        val attempt = slots.flatMap { it.attempts }.firstOrNull { it.attemptId == series.attemptId }
            ?: error("Landmark series references an unknown attempt")
        require(attempt.analysisSessionId == series.analysisSessionId)
        val result = requireNotNull(attempt.result) { "Finalized landmark series requires a terminal result" }
        require(result.resultId == series.resultId && result.assessmentType == series.assessmentType)
        require(attempt.status.name == series.status.name && result.status.name == series.status.name) {
            "Landmark series status does not match the canonical attempt/result"
        }

        val row = LandmarkSeriesEntity(
            seriesId = series.seriesId,
            schemaVersion = series.schemaVersion,
            messageId = envelope.messageId,
            profileId = series.profileId,
            assessmentSessionId = series.assessmentSessionId,
            attemptId = series.attemptId,
            analysisSessionId = series.analysisSessionId,
            resultId = series.resultId,
            assessmentType = series.assessmentType.name,
            status = series.status.name,
            targetFps = series.targetFps,
            startedAt = series.startedAt,
            completedAt = series.completedAt,
            sampleCount = series.samples.size,
            samplesJson = LandmarkSeriesJsonCodec.encodeSamples(series.samples),
            storedAt = storedAt,
        )
        if (dao.insert(row) != -1L) return LandmarkPersistReceipt(envelope, LandmarkPersistResult.APPLIED, storedAt)
        val existing = dao.getBySeriesId(series.seriesId) ?: dao.getByMessageId(envelope.messageId)
            ?: error("Landmark series insert was ignored without an existing receipt")
        require(existing == row.copy(storedAt = existing.storedAt)) { "Landmark messageId/seriesId was reused with different content" }
        return LandmarkPersistReceipt(envelope, LandmarkPersistResult.DUPLICATE, existing.storedAt)
    }
}
