package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Edits a `tool_result` that is already in the conversation.
 *
 * There is one caller: the Continue button, which appears when a tool returned more than
 * [AICodingAgent.run]'s `maxToolOutputTokens` allowed. The output was withheld and a note took its
 * place, the user has trimmed the real thing by hand, and this is what puts their version back where
 * the output would have gone -- so the model reads it as the tool's own answer rather than as a
 * second-hand quotation of it.
 *
 * Rewriting history that has already been sent would be a poor idea, and this never does: the turn
 * that withheld the output ended before the request carrying the note could go out, so the block
 * being edited here has never been over the wire. The next request is its first, and the prompt
 * cache has no memory of what it used to say.
 */
object ToolResults {

    /**
     * Puts [content] in the `tool_result` answering [toolUseId], and says whether it found one.
     *
     * Searched from the end, because that is where it will be: this runs on the round the turn died
     * in, which is the last thing in the conversation. False means the block is gone -- a chat
     * reloaded from disk, or a compaction pass that reached it -- and the caller's cue to leave the
     * conversation alone rather than to invent somewhere to put the text.
     *
     * The message is rebuilt rather than edited in place. A [ChatMessage] holds the same [JsonArray]
     * the history was saved from, and mutating it would reach the copy [com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService]
     * is holding as well.
     */
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
