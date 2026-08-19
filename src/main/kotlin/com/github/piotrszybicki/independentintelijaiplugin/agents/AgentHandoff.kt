package com.github.piotrszybicki.independentintelijaiplugin.agents

data class AgentHandoff(
    val agentName: String,
    val specPath: String,
    val parentChatId: String,
    val childChatId: String? = null,
    val state: String = DRAFT,
) {

    val isSettled: Boolean get() = state != DRAFT

    companion object {
        const val DRAFT = "draft"
        const val PROCEEDED = "proceeded"
        const val CANCELLED = "cancelled"
    }
}

data class AgentSession(
    val agentName: String,
    val parentChatId: String?,
    val specPath: String?,
)
