package com.steply.app.sync

import com.steply.app.data.local.dao.AssessmentSessionDao
import com.steply.app.data.local.dao.AssessmentSummaryDao
import com.steply.app.data.local.dao.LandmarkSeriesDao
import com.steply.app.data.local.entities.AssessmentMessageReceiptEntity
import com.steply.app.data.local.entities.AssessmentResultReceiptEntity
import com.steply.app.data.local.entities.AssessmentSessionEntity
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.data.local.entities.LandmarkSeriesEntity
import com.steply.app.data.repository.AssessmentSessionRepository
import com.steply.app.data.repository.AssessmentUpdateResult
import com.steply.app.data.repository.LandmarkPersistResult
import com.steply.app.data.repository.LandmarkSeriesRepository
import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.ASSESSMENT_SUMMARY_SCHEMA_VERSION
import com.steply.app.domain.model.AssessmentFunctionalTests
import com.steply.app.domain.model.AssessmentPrescription
import com.steply.app.domain.model.AssessmentResultStatus
import com.steply.app.domain.model.AssessmentSessionEnvelope
import com.steply.app.domain.model.AssessmentSessionStatus
import com.steply.app.domain.model.AssessmentSlotStatus
import com.steply.app.domain.model.AssessmentTestSlot
import com.steply.app.domain.model.ApprovalStatus
import com.steply.app.domain.model.ExerciseId
import com.steply.app.domain.model.ExerciseLevel
import com.steply.app.domain.model.OtagoPlanStatus
import com.steply.app.domain.model.PrescriptionStatus
import com.steply.app.domain.model.STEADI_RULE_VERSION
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.SteadiScore
import com.steply.app.domain.model.SteadiStatus
import com.steply.app.domain.model.SupportRequirement
import com.steply.app.domain.model.VulnerabilityId
import com.steply.app.domain.model.WeightMode
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFinalJsonContractTest {
    @Test
    fun `REQ-S3-CONTRACT actual Web HIGH and BLOCKED plans satisfy the Mobile strict contract`() {
        val pendingPath = System.getenv("STEPLY_WEB_HIGH_PENDING_PRESCRIPTION_PATH")
        val approvedPath = System.getenv("STEPLY_WEB_HIGH_APPROVED_PRESCRIPTION_PATH")
        val blockedPath = System.getenv("STEPLY_WEB_BLOCKED_PRESCRIPTION_PATH")
        assumeTrue(
            "cross-runtime prescription contract runs through scripts/check-stage5-web-mobile-contract.mjs",
            !pendingPath.isNullOrBlank() && !approvedPath.isNullOrBlank() && !blockedPath.isNullOrBlank(),
        )

        val pending = OtagoPrescriptionContract.decode(JSONObject(File(requireNotNull(pendingPath)).readText()))
        assertEquals(OtagoPlanStatus.PENDING_PROFESSIONAL_REVIEW, pending.status)
        assertEquals(ApprovalStatus.PENDING, pending.professionalApproval.status)
        assertTrue(pending.requiresProfessionalReview)
        assertEquals(null, pending.walkingPlan)
        assertTrue(pending.selectedExercises.all { it.weightMode == WeightMode.NONE })
        assertTrue(pending.selectedExercises.filter { it.exerciseId in setOf(ExerciseId.S4, ExerciseId.S5) }.all {
            it.level == ExerciseLevel.C && it.supportRequirement == SupportRequirement.STABLE_SUPPORT
        })
        assertTrue(pending.selectedExercises.filter { it.exerciseId !in setOf(ExerciseId.S4, ExerciseId.S5) }.all {
            it.level == ExerciseLevel.A
        })

        val approved = OtagoPrescriptionContract.decode(JSONObject(File(requireNotNull(approvedPath)).readText()))
        assertEquals(OtagoPlanStatus.ACTIVE, approved.status)
        assertEquals(ApprovalStatus.APPROVED, approved.professionalApproval.status)
        assertFalse(approved.requiresProfessionalReview)
        assertEquals(null, approved.walkingPlan)
        assertEquals(pending.selectedExercises, approved.selectedExercises)

        val blocked = OtagoPrescriptionContract.decode(JSONObject(File(requireNotNull(blockedPath)).readText()))
        assertEquals(OtagoPlanStatus.BLOCKED, blocked.status)
        assertEquals(listOf(ExerciseId.W1, ExerciseId.W2, ExerciseId.W3, ExerciseId.W4, ExerciseId.W5), blocked.warmups.map { it.exerciseId })
        assertTrue(blocked.selectedExercises.isEmpty())
        assertEquals(null, blocked.walkingPlan)
        assertTrue(blocked.progressionProposals.isEmpty())
    }

    @Test
    fun `REQ-S5-E2E actual Web final JSON survives strict codec and repository without video persistence`() = runBlocking {
        val fixturePathEnvironment = System.getenv("STEPLY_WEB_FINAL_JSON_PATH")
        val landmarkPathEnvironment = System.getenv("STEPLY_WEB_LANDMARK_JSON_PATH")
        val videoMarkerEnvironment = System.getenv("STEPLY_VIDEO_MARKER")
        assumeTrue(
            "cross-runtime contract test runs through scripts/check-stage5-web-mobile-contract.mjs",
            !fixturePathEnvironment.isNullOrBlank() &&
                !landmarkPathEnvironment.isNullOrBlank() &&
                !videoMarkerEnvironment.isNullOrBlank(),
        )
        val fixturePath = requireNotNull(fixturePathEnvironment)
        val landmarkPath = requireNotNull(landmarkPathEnvironment)
        val videoMarker = requireNotNull(videoMarkerEnvironment)
        val rawWebEnvelope = File(fixturePath).readText()

        assertFalse("raw video marker must not enter final JSON", rawWebEnvelope.contains(videoMarker))
        assertNoRawVideoFields(JSONObject(rawWebEnvelope))

        val decoded = AssessmentSessionJsonCodec.decode(rawWebEnvelope)
        val session = decoded.session
        val chair = requireNotNull(session.functionalTests.chairStand30s.acceptedResult)
        val chairMeasurements = requireNotNull(chair.chairStand)
        assertEquals(11, chairMeasurements.observedRepetitions)
        assertEquals(9, chairMeasurements.completedRepetitions)
        assertEquals(0, chairMeasurements.cdcScoredRepetitions)

        val balance = requireNotNull(session.functionalTests.fourStageBalance.acceptedResult)
        val balanceMeasurements = requireNotNull(balance.balance)
        assertEquals(listOf(10.0, 10.0, 7.25, 0.0), balanceMeasurements.stages.map { it.holdSeconds })
        val tandem = balanceMeasurements.stages.single { it.stage.name == "TANDEM" }
        assertEquals(7.25, tandem.holdSeconds, 0.0)
        assertEquals(0.012345, requireNotNull(tandem.sway).mlRmsM ?: -1.0, 0.0)
        assertEquals(1.753086 + 0.02, tandem.sway.initialToStaticRatio ?: -1.0, 0.0)
        assertEquals(1.818383 + 0.02, tandem.sway.mlToApRatio ?: -1.0, 0.0)

        val invalidAttempt = session.functionalTests.fourStageBalance.attempts.single { attempt ->
            attempt.result?.resultId == "stage5-balance-invalid"
        }
        assertEquals(AssessmentAttemptStatus.INVALID, invalidAttempt.status)
        assertEquals(AssessmentResultStatus.INVALID, invalidAttempt.result?.status)
        assertEquals(0.2001, invalidAttempt.result?.quality?.g3ViolationRatio ?: -1.0, 0.0)
        assertEquals(true, invalidAttempt.result?.quality?.excludeFromTrends)
        assertTrue(session.vulnerabilityAssessment?.activeIds.orEmpty().containsAll(listOf(VulnerabilityId.V6, VulnerabilityId.V7)))
        assertNotNull(session.exercisePrescription.plan)
        assertTrue(session.exercisePrescription.plan?.selectedExercises.orEmpty().isNotEmpty())

        val assessmentDao = ContractAssessmentSessionDao(seedEntity(decoded))
        val summaryDao = ContractAssessmentSummaryDao()
        val repository = AssessmentSessionRepository(assessmentDao, summaryDao)

        assertEquals(AssessmentUpdateResult.APPLIED, repository.applyEnvelope(rawWebEnvelope, activate = true))
        assertEquals(AssessmentUpdateResult.DUPLICATE, repository.applyEnvelope(rawWebEnvelope, activate = true))
        assertEquals(1, summaryDao.items.size)
        assertEquals(4, assessmentDao.resultReceipts.size)

        val summary = summaryDao.items.single()
        assertEquals(ASSESSMENT_SUMMARY_SCHEMA_VERSION, summary.schemaVersion)
        assertEquals(0, summary.chairStandRepetitions)
        assertEquals(
            listOf(10.0, 10.0, 7.25, 0.0),
            listOf(summary.sideBySideSeconds, summary.semiTandemSeconds, summary.tandemSeconds, summary.oneLegSeconds),
        )
        assertTrue(summary.valid)
        assertFalse(
            "canonical assessment_summaries must not persist operational quality scores",
            AssessmentSummaryEntity::class.java.declaredFields.any { field ->
                field.name.contains("quality", ignoreCase = true)
            },
        )

        val persisted = requireNotNull(repository.getActive()).envelope
        assertEquals(decoded, persisted)
        val persistedJson = AssessmentSessionJsonCodec.encode(persisted)
        assertFalse(persistedJson.contains(videoMarker))
        assertNoRawVideoFields(JSONObject(persistedJson))

        val rawLandmarkEnvelope = File(landmarkPath).readText()
        assertFalse("landmark-only transfer must not contain raw video", rawLandmarkEnvelope.contains(videoMarker))
        assertNoRawVideoFields(JSONObject(rawLandmarkEnvelope))
        val landmarkDao = ContractLandmarkSeriesDao()
        val landmarkRepository = LandmarkSeriesRepository(landmarkDao, assessmentDao)
        val storedAt = 1_800_000_000_000L
        val (landmarkEnvelope, firstLandmarkResult) = landmarkRepository.persistFinalized(rawLandmarkEnvelope, storedAt)
        val (_, duplicateLandmarkResult) = landmarkRepository.persistFinalized(rawLandmarkEnvelope, storedAt)
        assertEquals(LandmarkPersistResult.APPLIED, firstLandmarkResult)
        assertEquals(LandmarkPersistResult.DUPLICATE, duplicateLandmarkResult)
        assertEquals(-0.01, landmarkEnvelope.series.samples.single().normalizedLandmarks[0].x, 0.0)
        assertEquals(1.01, landmarkEnvelope.series.samples.single().normalizedLandmarks[1].x, 0.0)
        assertEquals(0.95, landmarkEnvelope.series.samples.single().normalizedLandmarks[0].visibility, 0.0)
        assertEquals(1, landmarkDao.items.size)
        val storedSamples = JSONArray(landmarkDao.items.single().samplesJson)
        val storedNormalized = storedSamples.getJSONObject(0).getJSONArray("normalizedLandmarks")
        assertEquals(-0.01, storedNormalized.getJSONObject(0).getDouble("x"), 0.0)
        assertEquals(1.01, storedNormalized.getJSONObject(1).getDouble("x"), 0.0)
        assertFalse(landmarkDao.items.single().samplesJson.contains(videoMarker))
        assertNoRawVideoFields(storedSamples)
    }

    private fun seedEntity(finalEnvelope: AssessmentSessionEnvelope): AssessmentSessionEntity {
        val finalSession = finalEnvelope.session
        val emptySlot = AssessmentTestSlot(AssessmentSlotStatus.NOT_STARTED, null, null, emptyList())
        val seedSession = finalSession.copy(
            revision = 0L,
            status = AssessmentSessionStatus.IN_PROGRESS,
            functionalTests = AssessmentFunctionalTests(emptySlot, emptySlot),
            vulnerabilityAssessment = null,
            steadi = SteadiScore(
                status = SteadiStatus.NOT_SCORABLE,
                risk = SteadiRisk.NOT_SCORABLE,
                strengthProblem = null,
                balanceProblem = null,
                step1AtRisk = null,
                step2Problem = null,
                reasonCodes = listOf("ASSESSMENT_INCOMPLETE"),
                ruleVersion = STEADI_RULE_VERSION,
            ),
            exercisePrescription = AssessmentPrescription(PrescriptionStatus.NOT_GENERATED, null),
            updatedAt = finalSession.createdAt,
            completedAt = null,
        )
        val seedEnvelope = AssessmentSessionEnvelope("stage5-mobile-seed", 0L, seedSession)
        return AssessmentSessionEntity(
            assessmentSessionId = seedSession.assessmentSessionId,
            connectionSessionId = requireNotNull(seedSession.connectionSessionId),
            profileId = seedSession.profileId,
            serverUrl = "https://127.0.0.1:3000",
            candidateServerUrlsJson = JSONArray(listOf("https://127.0.0.1:3000")).toString(),
            expiresAtEpochMs = Long.MAX_VALUE,
            pairingToken = "stage5-contract-token",
            tlsCertSha256 = null,
            revision = 0L,
            lastMessageId = seedEnvelope.messageId,
            envelopeJson = AssessmentSessionJsonCodec.encode(seedEnvelope),
            isActive = true,
            createdAt = seedSession.createdAt,
            updatedAt = seedSession.updatedAt,
        )
    }

    private fun assertNoRawVideoFields(value: Any?) {
        val forbidden = setOf("frame", "frames", "video", "rawVideo", "jpeg", "imageData", "videoBytes")
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                assertFalse("raw video field is forbidden in persistent JSON: $key", key in forbidden)
                assertNoRawVideoFields(value.opt(key))
            }
            is JSONArray -> repeat(value.length()) { index -> assertNoRawVideoFields(value.opt(index)) }
        }
    }
}

private class ContractAssessmentSessionDao(seed: AssessmentSessionEntity) : AssessmentSessionDao {
    val rows = linkedMapOf(seed.assessmentSessionId to seed)
    val messageReceipts = linkedMapOf<String, AssessmentMessageReceiptEntity>()
    val resultReceipts = linkedMapOf<String, AssessmentResultReceiptEntity>()
    private val active = MutableStateFlow<AssessmentSessionEntity?>(seed)

    override fun observeActive(): Flow<AssessmentSessionEntity?> = active
    override fun observeByProfileId(profileId: String): Flow<List<AssessmentSessionEntity>> =
        active.map { rows.values.filter { row -> row.profileId == profileId } }
    override suspend fun getActive(): AssessmentSessionEntity? = active.value
    override suspend fun getById(assessmentSessionId: String): AssessmentSessionEntity? = rows[assessmentSessionId]
    override suspend fun getByProfileId(profileId: String): List<AssessmentSessionEntity> = rows.values.filter { it.profileId == profileId }
    override suspend fun upsert(session: AssessmentSessionEntity) {
        rows[session.assessmentSessionId] = session
        if (session.isActive) active.value = session
    }
    override suspend fun deactivateOthers(assessmentSessionId: String) {
        rows.replaceAll { id, row -> if (id == assessmentSessionId) row else row.copy(isActive = false) }
        active.value = rows.values.firstOrNull { it.isActive }
    }
    override suspend fun deactivate(assessmentSessionId: String) {
        rows[assessmentSessionId]?.let { rows[assessmentSessionId] = it.copy(isActive = false) }
        active.value = rows.values.firstOrNull { it.isActive }
    }
    override suspend fun getMessageReceipt(messageId: String): AssessmentMessageReceiptEntity? = messageReceipts[messageId]
    override suspend fun insertMessageReceipt(receipt: AssessmentMessageReceiptEntity): Long {
        if (messageReceipts.putIfAbsent(receipt.messageId, receipt) != null) return -1L
        return messageReceipts.size.toLong()
    }
    override suspend fun getResultReceipt(resultId: String): AssessmentResultReceiptEntity? = resultReceipts[resultId]
    override suspend fun insertResultReceipt(receipt: AssessmentResultReceiptEntity): Long {
        if (resultReceipts.putIfAbsent(receipt.resultId, receipt) != null) return -1L
        return resultReceipts.size.toLong()
    }
}

private class ContractAssessmentSummaryDao : AssessmentSummaryDao {
    val items = mutableListOf<AssessmentSummaryEntity>()
    private val state = MutableStateFlow<List<AssessmentSummaryEntity>>(emptyList())

    override suspend fun upsert(summary: AssessmentSummaryEntity) {
        items.removeAll { it.assessmentSessionId == summary.assessmentSessionId }
        items += summary
        state.value = items.toList()
    }

    override fun observeValidByProfile(profileId: String): Flow<List<AssessmentSummaryEntity>> =
        state.map { values -> values.filter { it.profileId == profileId && it.valid } }

    override suspend fun getValidByProfile(profileId: String): List<AssessmentSummaryEntity> =
        items.filter { it.profileId == profileId && it.valid }
}

private class ContractLandmarkSeriesDao : LandmarkSeriesDao {
    val items = mutableListOf<LandmarkSeriesEntity>()

    override suspend fun insert(series: LandmarkSeriesEntity): Long {
        if (items.any { it.seriesId == series.seriesId || it.messageId == series.messageId }) return -1L
        items += series
        return items.size.toLong()
    }

    override suspend fun getBySeriesId(seriesId: String): LandmarkSeriesEntity? =
        items.firstOrNull { it.seriesId == seriesId }

    override suspend fun getByMessageId(messageId: String): LandmarkSeriesEntity? =
        items.firstOrNull { it.messageId == messageId }
}
