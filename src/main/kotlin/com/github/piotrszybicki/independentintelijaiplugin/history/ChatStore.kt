package com.github.piotrszybicki.independentintelijaiplugin.history

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn

interface ChatStore {
    fun load(id: String): StoredChat?

    fun save(
        chat: StoredChat,
        active: Boolean = true,
    )

    fun addReturn(
        chatId: String,
        returned: AgentReturn,
    ): Boolean

    fun delete(id: String)
}
