package com.steply.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assessment_sessions")
data class AssessmentSessionEntity(
    @PrimaryKey
    val assessmentSessionId: String,
    val connectionSessionId: String,
    val profileId: String,
    val serverUrl: String,
    val candidateServerUrlsJson: String,
    val expiresAtEpochMs: Long,
    val pairingToken: String,
    val tlsCertSha256: String?,
    val revision: Long,
    val lastMessageId: String,
    val envelopeJson: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

