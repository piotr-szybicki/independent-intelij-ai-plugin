package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ToolResults {

    fun replace(history: MutableList<ChatMessage>, toolUseId: String, content: String): Boolean {
        if (toolUseId.isEmpty()) return false

        for (i in history.indices.reversed()) {
            val message = history[i]
            if (message.role != "user") continue

            var found = false
            val copy = JsonArray()
            for (block in message.content) {
                val obj = block.takeIf { it.isJsonObject }?.asJsonObject
                if (obj == null ||
                    obj.get("type")?.asString != "tool_result" ||
                    obj.get("tool_use_id")?.asString != toolUseId
                ) {
                    copy.add(block)
                    continue
                }
                copy.add(JsonObject().apply {
                    addProperty("type", "tool_result")
                    addProperty("tool_use_id", toolUseId)
                    addProperty("content", content)
                })
                found = true
            }

            if (found) {
                history[i] = ChatMessage(message.role, copy)
                return true
            }
        }
        return false
    }
}
