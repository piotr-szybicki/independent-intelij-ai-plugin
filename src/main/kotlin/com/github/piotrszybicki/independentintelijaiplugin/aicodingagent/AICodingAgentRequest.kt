package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class AICodingAgentRequest(
    val model: String,
    val max_tokens: Int,
    val system: JsonArray?,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>?,
    val thinking: JsonObject?,
    val output_config: JsonObject?,
)
