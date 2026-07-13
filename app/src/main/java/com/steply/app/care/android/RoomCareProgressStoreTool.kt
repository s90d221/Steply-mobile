package com.steply.app.care.android

import com.steply.app.care.CareActionType
import com.steply.app.care.CareTool
import com.steply.app.care.CareToolRequest
import com.steply.app.care.CareToolResult
import com.steply.app.data.repository.CareAgentRepository

/**
 * The runner reserves the action in Room before invoking this tool. This adapter
 * verifies that durable receipt and never writes clinical assessment or plan data.
 */
class RoomCareProgressStoreTool(
    private val repository: CareAgentRepository,
) : CareTool {
    override suspend fun execute(request: CareToolRequest): CareToolResult {
        if (request.actionType !in allowedOperationalActions) {
            return CareToolResult(false, "PROGRESS_STORE_CLINICAL_WRITE_FORBIDDEN", retryable = false)
        }
        if (!repository.hasReservedAction(request.actionId)) {
            return CareToolResult(false, "PROGRESS_STORE_ACTION_NOT_RESERVED", retryable = true)
        }
        return CareToolResult(
            success = true,
            resultCode = "ROOM_ACTION_RECEIPT_CONFIRMED",
            resultReference = "care-action:${request.actionId}",
        )
    }

    private companion object {
        val allowedOperationalActions = setOf(
            CareActionType.STOP_SESSION,
            CareActionType.HOLD_PROGRESSION,
            CareActionType.PROPOSE_SPLIT_SESSION,
            CareActionType.MAINTAIN_PLAN,
        )
    }
}
