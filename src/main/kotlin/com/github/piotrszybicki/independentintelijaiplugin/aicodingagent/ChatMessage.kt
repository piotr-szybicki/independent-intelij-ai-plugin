package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class ChatMessage(
    val role: String,
    val content: JsonArray,
) {
    companion object {
        fun text(
            role: String,
            text: String,
        ): ChatMessage {
            val block =
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                }
            return ChatMessage(role, JsonArray().apply { add(block) })
        }
    }
}
