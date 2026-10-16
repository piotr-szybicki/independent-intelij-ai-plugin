package com.github.piotrszybicki.independentintelijaiplugin.history

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentReturn
import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentSession
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ChatMessage
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ContextMeter
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.SessionUsage
import com.github.piotrszybicki.independentintelijaiplugin.settings.ConversationTools

data class StoredChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val transcript: List<StoredRow>,
    val usage: SessionUsage? = null,
    val context: ContextMeter.State? = null,
    val configurationName: String? = null,
    val model: String? = null,
    val agent: AgentSession? = null,
    val returns: List<AgentReturn>? = null,
    val conversationTools: ConversationTools? = null,
)
