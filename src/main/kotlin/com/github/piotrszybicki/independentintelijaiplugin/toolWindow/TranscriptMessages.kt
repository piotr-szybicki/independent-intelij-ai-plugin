package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal interface TranscriptMessages : RestoreCheckpointMessages {
    fun addUserMessage(markdown: String)

    fun addHandoff(
        agentName: String,
        description: String,
        specName: String,
        state: String,
        worktreePath: String? = null,
        worktreeBranch: String? = null,
        onOpenSpec: () -> Unit,
        onProceed: () -> Boolean,
        onCancel: () -> Unit,
        onOpenChat: () -> Unit,
    )

    fun lastTurnMarkdown(): String

    fun addAssistantMessage(markdown: String)
}
