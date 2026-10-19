package com.github.piotrszybicki.independentintelijaiplugin.history

internal data class ChatHistoryEntry(
    val chat: ChatSummary,
    val depth: Int,
)
