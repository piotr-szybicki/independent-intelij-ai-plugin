package com.github.piotrszybicki.independentintelijaiplugin.agents

data class AgentHandoff(
    val agentName: String,
    val specPath: String,
    val parentChatId: String,
    val childChatId: String? = null,
    val state: String = DRAFT,
    val worktreePath: String? = null,
    val worktreeBranch: String? = null,
    val creationError: String? = null,
) {
    val isSettled: Boolean get() = state != DRAFT

    companion object {
        const val DRAFT = "draft"
        const val PROCEEDED = "proceeded"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
    }
}
