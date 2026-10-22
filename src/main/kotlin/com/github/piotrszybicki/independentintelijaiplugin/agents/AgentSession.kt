package com.github.piotrszybicki.independentintelijaiplugin.agents

data class AgentSession(
    val agentName: String,
    val parentChatId: String?,
    val specPath: String?,
    val worktreePath: String? = null,
    val worktreeBranch: String? = null,
)
