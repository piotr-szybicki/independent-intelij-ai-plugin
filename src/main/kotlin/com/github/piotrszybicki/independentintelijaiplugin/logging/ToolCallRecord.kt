package com.github.piotrszybicki.independentintelijaiplugin.logging

data class ToolCallRecord(
    val conversationId: String,
    val requestId: String,
    val ordinal: Int,
    val toolUseId: String,
    val toolName: String,
    val arguments: String,
    val result: String,
    val outcome: String,
    val argumentTokens: Int,
    val resultTokens: Int,
    val durationMillis: Long,
)
