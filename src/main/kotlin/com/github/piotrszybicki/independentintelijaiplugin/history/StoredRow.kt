package com.github.piotrszybicki.independentintelijaiplugin.history

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentHandoff

data class StoredRow(
    val kind: String,
    val text: String = "",
    val name: String = "",
    val summary: String = "",
    val details: String = "",
    val status: String? = null,
    val requestId: String? = null,
    val handoff: AgentHandoff? = null,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val ERROR = "error"
        const val HANDOFF = "handoff"
        const val THINKING = "thinking"
        const val COST = "cost"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}
