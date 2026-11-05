package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

data class ToolExecutionContext(
    val conversationId: String,
    val requestId: String,
    val toolUseId: String,
)
