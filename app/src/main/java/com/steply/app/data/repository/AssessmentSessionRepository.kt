package com.steply.app.data.repository

import com.steply.app.data.local.dao.AssessmentSessionDao
import com.steply.app.data.local.dao.AssessmentSummaryDao
import com.steply.app.data.local.entities.AssessmentSessionEntity
import com.steply.app.data.local.entities.AssessmentMessageReceiptEntity
import com.steply.app.data.local.entities.AssessmentResultReceiptEntity
import com.steply.app.data.local.entities.AssessmentSummaryEntity
import com.steply.app.domain.model.AssessmentFunctionalTests
import com.steply.app.domain.model.ASSESSMENT_SUMMARY_SCHEMA_VERSION
import com.steply.app.domain.model.BalanceStage
import com.steply.app.domain.model.AssessmentPrescription
import com.steply.app.domain.model.AssessmentOperationalContext
import com.steply.app.domain.model.AssessmentProfileSnapshot
import com.steply.app.domain.model.AssessmentScreening
import com.steply.app.domain.model.AssessmentSession
import com.steply.app.domain.model.AssessmentSessionEnvelope
import com.steply.app.domain.model.AssessmentSessionStatus
import com.steply.app.domain.model.AssessmentSex
import com.steply.app.domain.model.AssessmentSlotStatus
import com.steply.app.domain.model.AssessmentTestSlot
import com.steply.app.domain.model.PrescriptionStatus
import com.steply.app.domain.model.STEADI_RULE_VERSION
import com.steply.app.domain.model.STAGE2_OPERATIONAL_CONFIG_VERSION
import com.steply.app.domain.model.SteadiRisk
import com.steply.app.domain.model.SteadiScore
import com.steply.app.domain.model.SteadiStatus
import com.steply.app.domain.model.UserProfile
import com.steply.app.domain.model.hasScoredAggregate
import com.steply.app.sync.AssessmentSessionJsonCodec
import com.steply.app.sync.SteplyWebSessionPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PersistedAssessmentSession(
    val envelope: AssessmentSessionEnvelope,
    val connection: SteplyWebSessionPayload,
    val isActive: Boolean,
)

enum class AssessmentUpdateResult {
    APPLIED,
    DUPLICATE,
    STALE,
}

class AssessmentSessionRepository(
    private val assessmentSessionDao: AssessmentSessionDao,
    private val assessmentSummaryDao: AssessmentSummaryDao? = null,
) {
    private val updateMutex = Mutex()

    fun observeActive(): Flow<PersistedAssessmentSession?> {
        return assessmentSessionDao.observeActive().map { it?.toDomainOrNull() }
    }

    suspend fun getActive(): PersistedAssessmentSession? = assessmentSessionDao.getActive()?.toDomainOrNull()

    suspend fun getByProfileId(profileId: String): List<PersistedAssessmentSession> =
        assessmentSessionDao.getByProfileId(profileId).mapNotNull { it.toDomainOrNull() }

    fun observeByProfileId(profileId: String): Flow<List<PersistedAssessmentSession>> =
        assessmentSessionDao.observeByProfileId(profileId).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    suspend fun createPending(
        connection: SteplyWebSessionPayload,
        profile: UserProfile,
        now: Long = System.currentTimeMillis(),
    ): PersistedAssessmentSession {
        val assessmentSessionId = UUID.randomUUID().toString()
        val envelope = AssessmentSessionEnvelope(
            messageId = "mobile-initial-$assessmentSessionId",
            baseRevision = 0L,
            session = AssessmentSession(
                assessmentSessionId = assessmentSessionId,
                connectionSessionId = connection.sessionId,
                profileId = profile.id,
                revision = 0L,
                status = AssessmentSessionStatus.IN_PROGRESS,
                screening = AssessmentScreening(
                    status = AssessmentSlotStatus.NOT_STARTED,
                    fallenPastYear = null,
                    feelsUnsteady = null,
                    worriedAboutFalling = null,
                    fallCount = null,
                    injuriousFall = null,
                ),
                profileSnapshot = AssessmentProfileSnapshot(
                    birthYear = profile.birthYear,
                    ageYears = profile.age,
                    sex = profile.gender.toAssessmentSexOrNull(),
                ),
                operationalContext = AssessmentOperationalContext(
                    operationalConfigVersion = STAGE2_OPERATIONAL_CONFIG_VERSION,
                    supportRoiNormalized = null,
                ),
                functionalTests = AssessmentFunctionalTests(
                    fourStageBalance = AssessmentTestSlot(
                        status = AssessmentSlotStatus.NOT_STARTED,
                        acceptedAttemptId = null,
                        acceptedResult = null,
                        attempts = emptyList(),
                    ),
                    chairStand30s = AssessmentTestSlot(
                        status = AssessmentSlotStatus.NOT_STARTED,
                        acceptedAttemptId = null,
                        acceptedResult = null,
                        attempts = emptyList(),
                    ),
                ),
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
                exercisePrescription = AssessmentPrescription(PrescriptionStatus.NOT_GENERATED, plan = null),
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            ),
        )
        val persisted = PersistedAssessmentSession(envelope, connection, isActive = false)
        assessmentSessionDao.upsert(persisted.toEntity())
        return persisted
    }

    suspend fun activate(
        assessmentSessionId: String,
        responseEnvelopeJson: String? = null,
    ): PersistedAssessmentSession {
        if (!responseEnvelopeJson.isNullOrBlank()) {
            val response = JSONObject(responseEnvelopeJson)
            val snapshot = response.optJSONObject("assessmentSession")
                ?: response.optJSONObject("session")?.optJSONObject("assessmentSession")
            if (snapshot != null) {
                val decoded = AssessmentSessionJsonCodec.decodeSession(snapshot)
                val envelope = AssessmentSessionEnvelope(
                    messageId = "connect-response-${decoded.assessmentSessionId}-${decoded.revision}",
                    baseRevision = (decoded.revision - 1L).coerceAtLeast(0L),
                    session = decoded,
                )
                applyEnvelope(AssessmentSessionJsonCodec.encode(envelope), activate = true)
            } else if (response.optString("type") == "assessment-session.updated") {
                applyEnvelope(responseEnvelopeJson, activate = true)
            }
        }
        val existing = requireNotNull(assessmentSessionDao.getById(assessmentSessionId)?.toDomainOrNull()) {
            "Assessment session was not persisted"
        }
        assessmentSessionDao.deactivateOthers(assessmentSessionId)
        val active = existing.copy(isActive = true)
        assessmentSessionDao.upsert(active.toEntity())
        ensureAggregateSummary(active.envelope)
        return active
    }

    suspend fun applyEnvelope(
        rawEnvelopeJson: String,
        activate: Boolean = false,
    ): AssessmentUpdateResult = updateMutex.withLock {
        val incoming = AssessmentSessionJsonCodec.decode(rawEnvelopeJson)
        assessmentSessionDao.getMessageReceipt(incoming.messageId)?.let { receipt ->
            if (receipt.assessmentSessionId != incoming.session.assessmentSessionId || receipt.revision != incoming.revision) {
                throw IllegalArgumentException("messageId was reused with a different assessment update")
            }
            ensureAggregateSummary(incoming)
            return@withLock AssessmentUpdateResult.DUPLICATE
        }
        val currentEntity = assessmentSessionDao.getById(incoming.session.assessmentSessionId)
            ?: throw IllegalArgumentException("Unknown assessmentSessionId: ${incoming.session.assessmentSessionId}")
        val current = requireNotNull(currentEntity.toDomainOrNull()) { "Stored assessment session is corrupt" }
        if (incoming.session.connectionSessionId != current.envelope.session.connectionSessionId) {
            throw IllegalArgumentException("connectionSessionId does not match the persisted assessment")
        }
        if (incoming.session.profileId != current.envelope.session.profileId) {
            throw IllegalArgumentException("profileId does not match the persisted assessment")
        }

        val incomingResults = listOf(
            incoming.session.functionalTests.fourStageBalance,
            incoming.session.functionalTests.chairStand30s,
        ).flatMap { slot -> listOfNotNull(slot.acceptedResult) + slot.attempts.mapNotNull { it.result } }
            .distinctBy { it.resultId }
        incomingResults.forEach { result ->
            assessmentSessionDao.getResultReceipt(result.resultId)?.let { receipt ->
                val signature = AssessmentSessionJsonCodec.canonicalResultJson(result, incoming.session.schemaVersion)
                if (receipt.assessmentSessionId != incoming.session.assessmentSessionId ||
                    receipt.assessmentType != result.assessmentType.name ||
                    receipt.attemptId != result.attemptId ||
                    receipt.resultSignature != signature
                ) {
                    throw IllegalArgumentException("resultId was reused with a different result")
                }
            }
        }

        val result = when {
            incoming.revision < current.envelope.revision -> AssessmentUpdateResult.STALE
            incoming.revision == current.envelope.revision -> AssessmentUpdateResult.STALE
            else -> {
                val updated = current.copy(
                    envelope = incoming,
                    isActive = activate || current.isActive,
                )
                if (updated.isActive) assessmentSessionDao.deactivateOthers(incoming.session.assessmentSessionId)
                assessmentSessionDao.upsert(updated.toEntity())
                assessmentSessionDao.insertMessageReceipt(
                    AssessmentMessageReceiptEntity(
                        messageId = incoming.messageId,
                        assessmentSessionId = incoming.session.assessmentSessionId,
                        revision = incoming.revision,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
                incomingResults.forEach { result ->
                    assessmentSessionDao.insertResultReceipt(
                        AssessmentResultReceiptEntity(
                            resultId = result.resultId,
                            assessmentSessionId = incoming.session.assessmentSessionId,
                            assessmentType = result.assessmentType.name,
                            attemptId = result.attemptId,
                            resultSignature = AssessmentSessionJsonCodec.canonicalResultJson(
                                result,
                                incoming.session.schemaVersion,
                            ),
                        ),
                    )
                }
                AssessmentUpdateResult.APPLIED
            }
        }
        if (result != AssessmentUpdateResult.STALE) ensureAggregateSummary(incoming)
        result
    }

    suspend fun deactivate(assessmentSessionId: String) {
        assessmentSessionDao.deactivate(assessmentSessionId)
    }

    private suspend fun ensureAggregateSummary(envelope: AssessmentSessionEnvelope) {
        val session = envelope.session
        if (!session.hasScoredAggregate()) return
        val chair = requireNotNull(session.functionalTests.chairStand30s.acceptedResult?.chairStand)
        val stages = requireNotNull(session.functionalTests.fourStageBalance.acceptedResult?.balance).stages
            .associateBy { it.stage }
        val qualityValid = session.functionalTests.chairStand30s.acceptedResult?.quality?.excludeFromTrends != true &&
            session.functionalTests.fourStageBalance.acceptedResult?.quality?.excludeFromTrends != true
        if (qualityValid) {
            assessmentSummaryDao?.upsert(
                AssessmentSummaryEntity(
                    assessmentSessionId = session.assessmentSessionId,
                    schemaVersion = ASSESSMENT_SUMMARY_SCHEMA_VERSION,
                    profileId = session.profileId,
                    completedAt = requireNotNull(session.completedAt),
                    risk = session.steadi.risk.name,
                    vulnerabilityIdsJson = JSONArray(session.vulnerabilityAssessment?.activeIds.orEmpty().map { it.name }).toString(),
                    chairStandRepetitions = chair.cdcScoredRepetitions,
                    sideBySideSeconds = requireNotNull(stages[BalanceStage.SIDE_BY_SIDE]).holdSeconds,
                    semiTandemSeconds = requireNotNull(stages[BalanceStage.SEMI_TANDEM]).holdSeconds,
                    tandemSeconds = requireNotNull(stages[BalanceStage.TANDEM]).holdSeconds,
                    oneLegSeconds = requireNotNull(stages[BalanceStage.ONE_LEG]).holdSeconds,
                    valid = true,
                    updatedAt = session.updatedAt,
                ),
            )
        }
    }
}

private fun PersistedAssessmentSession.toEntity(): AssessmentSessionEntity {
    return AssessmentSessionEntity(
        assessmentSessionId = envelope.session.assessmentSessionId,
        connectionSessionId = requireNotNull(envelope.session.connectionSessionId),
        profileId = envelope.session.profileId,
        serverUrl = connection.serverUrl,
        candidateServerUrlsJson = JSONArray(connection.candidateServerUrls).toString(),
        expiresAtEpochMs = connection.expiresAtEpochMs,
        pairingToken = connection.pairingToken,
        tlsCertSha256 = connection.tlsCertSha256,
        revision = envelope.revision,
        lastMessageId = envelope.messageId,
        envelopeJson = AssessmentSessionJsonCodec.encode(envelope),
        isActive = isActive,
        createdAt = envelope.session.createdAt,
        updatedAt = envelope.session.updatedAt,
    )
}

private fun AssessmentSessionEntity.toDomainOrNull(): PersistedAssessmentSession? {
    return runCatching {
        val envelope = AssessmentSessionJsonCodec.decode(envelopeJson)
        require(envelope.session.assessmentSessionId == assessmentSessionId)
        val candidatesJson = JSONArray(candidateServerUrlsJson)
        val candidates = buildList {
            for (index in 0 until candidatesJson.length()) {
                candidatesJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        PersistedAssessmentSession(
            envelope = envelope,
            connection = SteplyWebSessionPayload(
                sessionId = connectionSessionId,
                serverUrl = serverUrl,
                candidateServerUrls = candidates.ifEmpty { listOf(serverUrl) },
                expiresAtEpochMs = expiresAtEpochMs,
                pairingToken = pairingToken,
                tlsCertSha256 = tlsCertSha256,
            ),
            isActive = isActive,
        )
    }.getOrNull()
}

private fun String?.toAssessmentSexOrNull(): AssessmentSex? {
    return when (this?.trim()?.uppercase()) {
        "MALE", "M", "MAN" -> AssessmentSex.MALE
        "FEMALE", "F", "WOMAN" -> AssessmentSex.FEMALE
        else -> null
    }
}
