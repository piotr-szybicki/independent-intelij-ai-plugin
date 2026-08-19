package com.github.piotrszybicki.independentintelijaiplugin.agents

data class AgentReturn(
    val agentName: String,
    val chatId: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
)
