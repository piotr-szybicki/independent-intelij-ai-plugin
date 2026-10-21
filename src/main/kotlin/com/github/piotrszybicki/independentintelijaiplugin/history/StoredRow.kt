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
    // The model history immediately before a user prompt. It makes the prompt a durable restore point.
    val checkpointHistorySize: Int? = null,
    // The text the user typed, before attachments and skill instructions were prepended for the model.
    val promptDraft: String? = null,
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
