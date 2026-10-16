package com.github.piotrszybicki.independentintelijaiplugin.history

interface ChatHistoryIndex {
    fun chats(): List<ChatSummary>

    fun activeId(): String?

    fun setActiveId(id: String?)

    fun deleteAll()
}
