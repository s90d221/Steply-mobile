package com.steply.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assessment_message_receipts")
data class AssessmentMessageReceiptEntity(
    @PrimaryKey val messageId: String,
    val assessmentSessionId: String,
    val revision: Long,
    val receivedAt: Long,
)

@Entity(tableName = "assessment_result_receipts")
data class AssessmentResultReceiptEntity(
    @PrimaryKey val resultId: String,
    val assessmentSessionId: String,
    val assessmentType: String,
    val attemptId: String,
    val resultSignature: String,
)

