package com.steply.app.data.repository

import com.steply.app.data.local.dao.AssessmentSessionDao
import com.steply.app.data.local.dao.AssessmentSummaryDao
import com.steply.app.data.local.entities.AssessmentSessionEntity
import com.steply.app.data.local.entities.AssessmentMessageReceiptEntity
import com.steply.app.data.local.entities.AssessmentResultReceiptEntity
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.domain.model.AssessmentPrescription
import com.steply.app.domain.model.AssessmentSessionStatus
import com.steply.app.domain.model.PrescriptionStatus
import com.steply.app.domain.model.AssessmentAttemptStatus
import com.steply.app.domain.model.AssessmentResultStatus
import com.steply.app.domain.model.AssessmentSlotStatus
import com.steply.app.domain.model.UserProfile
import com.steply.app.sync.AssessmentSessionJsonCodec
import com.steply.app.sync.SteplyWebSessionPayload
import com.steply.app.sync.assessmentFixture
import com.steply.app.sync.scoredFixture
import com.steply.app.sync.otagoPlanFixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentSessionRepositoryTest {
    @Test
    fun `duplicate aggregate update is idempotent and creates one final history row`() = runBlocking {
        val assessmentDao = FakeAssessmentSessionDao()
        val summaryDao = FakeAssessmentSummaryDao()
        val repository = AssessmentSessionRepository(assessmentDao, summaryDao)
        val connection = SteplyWebSessionPayload(
            sessionId = "connection-1",
            serverUrl = "https://192.168.0.10:3000",
            expiresAtEpochMs = Long.MAX_VALUE,
            pairingToken = "0123456789abcdef",
        )
        val profile = UserProfile(
            id = "profile-1",
            displayName = "Tester",
            birthYear = 1956,
            gender = "MALE",
            heightCm = null,
            movementNotes = null,
            safetyNote = null,
            createdAt = 1L,
            updatedAt = 1L,
            archivedAt = null,
        )
        val pending = repository.createPending(connection, profile, now = 1_700_000_000_000L)
        repository.activate(pending.envelope.session.assessmentSessionId)
        val fixture = assessmentFixture(
            revision = 1L,
            sessionStatus = AssessmentSessionStatus.COMPLETED,
            steadi = scoredFixture(),
            prescription = AssessmentPrescription(PrescriptionStatus.ACTIVE, otagoPlanFixture()),
        )
        val completed = fixture.copy(
            messageId = "aggregate-final-1",
            session = fixture.session.copy(
                assessmentSessionId = pending.envelope.session.assessmentSessionId,
                connectionSessionId = connection.sessionId,
                profileId = profile.id,
                createdAt = pending.envelope.session.createdAt,
                exercisePrescription = fixture.session.exercisePrescription.copy(
                    plan = requireNotNull(fixture.session.exercisePrescription.plan).copy(
                        sourceAssessmentIds = listOf(pending.envelope.session.assessmentSessionId),
                    ),
                ),
            ),
        )
        val raw = AssessmentSessionJsonCodec.encode(completed)

        assertEquals(AssessmentUpdateResult.APPLIED, repository.applyEnvelope(raw))
        assertEquals(AssessmentUpdateResult.DUPLICATE, repository.applyEnvelope(raw))
        assertEquals(1, summaryDao.items.size)
        assertEquals(completed.session.assessmentSessionId, summaryDao.items.single().assessmentSessionId)
    }

    @Test
    fun `REQ-S6-5 invalid attempt persists deduplicates and is excluded from aggregate history`() = runBlocking {
        val assessmentDao = FakeAssessmentSessionDao()
        val summaryDao = FakeAssessmentSummaryDao()
        val repository = AssessmentSessionRepository(assessmentDao, summaryDao)
        val connection = SteplyWebSessionPayload(
            sessionId = "connection-1",
            serverUrl = "https://192.168.0.10:3000",
            expiresAtEpochMs = Long.MAX_VALUE,
            pairingToken = "0123456789abcdef",
        )
        val profile = UserProfile(
            id = "profile-1", displayName = "Tester", birthYear = 1956, gender = "MALE",
            heightCm = null, movementNotes = null, safetyNote = null,
            createdAt = 1L, updatedAt = 1L, archivedAt = null,
        )
        val pending = repository.createPending(connection, profile, now = 1_700_000_000_000L)
        repository.activate(pending.envelope.session.assessmentSessionId)
        val fixture = assessmentFixture(revision = 1L)
        val validChair = requireNotNull(fixture.session.functionalTests.chairStand30s.acceptedResult)
        val invalidResult = validChair.copy(
            resultId = "invalid-result-1",
            status = AssessmentResultStatus.INVALID,
            quality = requireNotNull(validChair.quality).copy(
                g3ViolationRatio = 0.21,
                invalidReasons = listOf("G3_VIOLATION_RATIO_EXCEEDED"),
                excludeFromTrends = true,
            ),
        )
        val invalidAttempt = fixture.session.functionalTests.chairStand30s.attempts.single().copy(
            status = AssessmentAttemptStatus.INVALID,
            resultHash = invalidResult.resultHash,
            result = invalidResult,
        )
        val incoming = fixture.copy(
            messageId = "invalid-message-1",
            session = fixture.session.copy(
                assessmentSessionId = pending.envelope.session.assessmentSessionId,
                connectionSessionId = connection.sessionId,
                profileId = profile.id,
                createdAt = pending.envelope.session.createdAt,
                functionalTests = fixture.session.functionalTests.copy(
                    chairStand30s = fixture.session.functionalTests.chairStand30s.copy(
                        status = AssessmentSlotStatus.NEEDS_RETRY,
                        acceptedAttemptId = null,
                        acceptedResult = null,
                        attempts = listOf(invalidAttempt),
                    ),
                    fourStageBalance = fixture.session.functionalTests.fourStageBalance.copy(
                        status = AssessmentSlotStatus.NOT_STARTED,
                        acceptedAttemptId = null,
                        acceptedResult = null,
                        attempts = emptyList(),
                    ),
                ),
            ),
        )
        val raw = AssessmentSessionJsonCodec.encode(incoming)

        assertEquals(AssessmentUpdateResult.APPLIED, repository.applyEnvelope(raw))
        assertEquals(AssessmentUpdateResult.DUPLICATE, repository.applyEnvelope(raw))
        assertEquals(0, summaryDao.items.size)

        val conflicting = incoming.copy(
            messageId = "invalid-message-2",
            baseRevision = 1L,
            session = incoming.session.copy(
                revision = 2L,
                updatedAt = incoming.session.updatedAt + 1L,
                functionalTests = incoming.session.functionalTests.copy(
                    chairStand30s = incoming.session.functionalTests.chairStand30s.copy(
                        attempts = listOf(
                            invalidAttempt.copy(
                                result = invalidResult.copy(
                                    quality = requireNotNull(invalidResult.quality).copy(g3ViolationRatio = 0.31),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val conflict = runCatching { repository.applyEnvelope(AssessmentSessionJsonCodec.encode(conflicting)) }

        assertTrue(conflict.exceptionOrNull()?.message?.contains("resultId was reused") == true)
        assertEquals(0, summaryDao.items.size)
    }
}

private class FakeAssessmentSessionDao : AssessmentSessionDao {
    private val rows = linkedMapOf<String, AssessmentSessionEntity>()
    private val messages = linkedMapOf<String, AssessmentMessageReceiptEntity>()
    private val results = linkedMapOf<String, AssessmentResultReceiptEntity>()
    private val active = MutableStateFlow<AssessmentSessionEntity?>(null)

    override fun observeActive(): Flow<AssessmentSessionEntity?> = active
    override fun observeByProfileId(profileId: String): Flow<List<AssessmentSessionEntity>> =
        active.map { rows.values.filter { row -> row.profileId == profileId } }
    override suspend fun getActive(): AssessmentSessionEntity? = rows.values.filter { it.isActive }.maxByOrNull { it.updatedAt }
    override suspend fun getById(assessmentSessionId: String): AssessmentSessionEntity? = rows[assessmentSessionId]
    override suspend fun getByProfileId(profileId: String): List<AssessmentSessionEntity> =
        rows.values.filter { it.profileId == profileId }
    override suspend fun upsert(session: AssessmentSessionEntity) {
        rows[session.assessmentSessionId] = session
        active.value = rows.values.filter { it.isActive }.maxByOrNull { it.updatedAt }
    }
    override suspend fun deactivateOthers(assessmentSessionId: String) {
        rows.replaceAll { id, row -> if (id == assessmentSessionId) row else row.copy(isActive = false) }
        active.value = rows.values.firstOrNull { it.isActive }
    }
    override suspend fun deactivate(assessmentSessionId: String) {
        rows[assessmentSessionId]?.let { rows[assessmentSessionId] = it.copy(isActive = false) }
        active.value = rows.values.firstOrNull { it.isActive }
    }
    override suspend fun getMessageReceipt(messageId: String): AssessmentMessageReceiptEntity? = messages[messageId]
    override suspend fun insertMessageReceipt(receipt: AssessmentMessageReceiptEntity): Long {
        if (messages.putIfAbsent(receipt.messageId, receipt) != null) return -1L
        return messages.size.toLong()
    }
    override suspend fun getResultReceipt(resultId: String): AssessmentResultReceiptEntity? = results[resultId]
    override suspend fun insertResultReceipt(receipt: AssessmentResultReceiptEntity): Long {
        if (results.putIfAbsent(receipt.resultId, receipt) != null) return -1L
        return results.size.toLong()
    }
}

private class FakeAssessmentSummaryDao : AssessmentSummaryDao {
    val items = mutableListOf<AssessmentSummaryEntity>()
    private val state = MutableStateFlow<List<AssessmentSummaryEntity>>(emptyList())

    override suspend fun upsert(summary: AssessmentSummaryEntity) {
        items.removeAll { it.assessmentSessionId == summary.assessmentSessionId }
        items += summary
        state.value = items.toList()
    }

    override fun observeValidByProfile(profileId: String): Flow<List<AssessmentSummaryEntity>> =
        state.map { rows -> rows.filter { it.profileId == profileId && it.valid } }

    override suspend fun getValidByProfile(profileId: String): List<AssessmentSummaryEntity> =
        items.filter { it.profileId == profileId && it.valid }
}
