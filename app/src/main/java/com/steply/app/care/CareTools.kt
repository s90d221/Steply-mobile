package com.steply.app.care

fun interface CareTool {
    suspend fun execute(request: CareToolRequest): CareToolResult
}

interface CareToolRegistry {
    fun tool(id: CareToolId): CareTool
}
