package com.github.piotrszybicki.independentintelijaiplugin.history

data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val agentName: String? = null,
)
