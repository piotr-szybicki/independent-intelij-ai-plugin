package com.github.piotrszybicki.independentintelijaiplugin.anthropic

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the translation between the plugin's Anthropic-shaped conversation and the OpenAI wire
 * formats.
 *
 * Everything here fails at the far end of a network call if it is wrong, as a 400 about a field
 * name -- which is exactly the class of bug that made this layer necessary in the first place.
 */
class OpenAiProtocolTest {

    private val tool = ToolDefinition(
        "read_project_file",
        "Reads a file.",
        JsonObject().apply { addProperty("type", "object") },
    )

    // --- Chat Completions: requests -----------------------------------------------------------------

    @Test
    fun `sends the cap under the name the reasoning models accept`() {
        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 4096, null, listOf(user("hi")), emptyList())

        assertEquals(4096, body.get("max_completion_tokens").asInt)
        assertFalse("max_tokens is rejected outright by the reasoning models", body.has("max_tokens"))
    }

    @Test
    fun `puts the system prompt in the first message`() {
        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 100, "be brief", listOf(user("hi")), emptyList())

        val first = body.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("system", first.get("role").asString)
        assertEquals("be brief", first.get("content").asString)
    }

    @Test
    fun `turns a tool_use block into a tool_call with its arguments as a string`() {
        val body = OpenAiProtocol.chatCompletionsRequest(
            "gpt-5", 100, null,
            listOf(user("go"), assistantToolUse("call_1", "read_project_file", """{"path":"a.kt"}""")),
            listOf(tool),
        )

        val assistant = body.getAsJsonArray("messages").last().asJsonObject
        assertEquals("assistant", assistant.get("role").asString)
        assertTrue("a turn that was only a tool call still needs the key", assistant.get("content").isJsonNull)

        val call = assistant.getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("call_1", call.get("id").asString)
        assertEquals("function", call.get("type").asString)
        assertEquals("read_project_file", call.getAsJsonObject("function").get("name").asString)
        assertEquals(
            JsonParser.parseString("""{"path":"a.kt"}"""),
            JsonParser.parseString(call.getAsJsonObject("function").get("arguments").asString),
        )
    }

    /** A `tool` message has to follow the assistant turn that asked for it, before anything else. */
    @Test
    fun `emits tool results before the text they were merged with`() {
        val mixed = ChatMessage("user", JsonArray().apply {
            add(toolResultBlock("call_1", "ok"))
            add(textBlock("and also do this"))
        })

        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 100, null, listOf(mixed), emptyList())

        val messages = body.getAsJsonArray("messages")
        assertEquals("tool", messages[0].asJsonObject.get("role").asString)
        assertEquals("call_1", messages[0].asJsonObject.get("tool_call_id").asString)
        assertEquals("ok", messages[0].asJsonObject.get("content").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
        assertEquals("and also do this", messages[1].asJsonObject.get("content").asString)
    }

    @Test
    fun `nests a tool definition under function`() {
        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 100, null, listOf(user("hi")), listOf(tool))

        val declared = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("function", declared.get("type").asString)
        assertEquals("read_project_file", declared.getAsJsonObject("function").get("name").asString)
        assertEquals("object", declared.getAsJsonObject("function").getAsJsonObject("parameters").get("type").asString)
    }

    @Test
    fun `drops a thinking block rather than sending it on`() {
        val thinking = ChatMessage("assistant", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "thinking")
                addProperty("thinking", "hmm")
            })
            add(textBlock("done"))
        })

        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 100, null, listOf(thinking), emptyList())

        assertEquals("done", body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
    }

    // --- Chat Completions: responses ----------------------------------------------------------------

    @Test
    fun `reads text and tool calls out of a choice`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"tool_calls","message":{"content":"looking","tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"read_project_file","arguments":"{\"path\":\"a.kt\"}"}}
            ]}}]}
        """))

        assertEquals("tool_use", turn.stopReason)
        assertEquals("text", turn.content[0].asJsonObject.get("type").asString)
        assertEquals("looking", turn.content[0].asJsonObject.get("text").asString)

        val call = turn.content[1].asJsonObject
        assertEquals("tool_use", call.get("type").asString)
        assertEquals("call_1", call.get("id").asString)
        assertEquals("read_project_file", call.get("name").asString)
        assertEquals("a.kt", call.getAsJsonObject("input").get("path").asString)
    }

    /** `max_tokens` is the reason the agent offers to continue, so the mapping has to be exact. */
    @Test
    fun `maps a length finish reason onto the one the agent acts on`() {
        val turn = OpenAiProtocol.parseChatCompletions(
            json("""{"choices":[{"finish_reason":"length","message":{"content":"half a sen"}}]}"""),
        )

        assertEquals("max_tokens", turn.stopReason)
    }

    @Test
    fun `infers tool use from the content when the server says stop anyway`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"stop","message":{"content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"read_project_file","arguments":"{}"}}
            ]}}]}
        """))

        assertEquals("tool_use", turn.stopReason)
        assertEquals(1, turn.content.size())
    }

    @Test
    fun `reads usage including the cached half of it`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],
             "usage":{"prompt_tokens":120,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":64}}}
        """))

        assertEquals(120, turn.usage?.input_tokens)
        assertEquals(7, turn.usage?.output_tokens)
        assertEquals(64, turn.usage?.cache_read_input_tokens)
    }

    @Test
    fun `survives a usage block whose details are JSON null`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":null}}
        """))

        assertEquals(0, turn.usage?.cache_read_input_tokens)
    }

    /** A reply cut off by the token limit leaves the arguments string ending mid-object. */
    @Test
    fun `reads truncated arguments as empty rather than failing the turn`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"length","message":{"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"read_project_file","arguments":"{\"path\":\"a"}}
            ]}}]}
        """))

        assertEquals(0, turn.content[0].asJsonObject.getAsJsonObject("input").size())
    }

    @Test
    fun `reports a response with no choices rather than returning nothing`() {
        val failure = runCatching { OpenAiProtocol.parseChatCompletions(json("""{"id":"x"}""")) }
        assertTrue(failure.exceptionOrNull() is AnthropicApiException)
    }

    // --- Responses: requests ------------------------------------------------------------------------

    @Test
    fun `sends the conversation as input, not messages`() {
        val body = OpenAiProtocol.responsesRequest("gpt-5", 100, "be brief", listOf(user("hi")), emptyList())

        assertFalse("the field this whole layer exists because of", body.has("messages"))
        assertEquals("be brief", body.get("instructions").asString)
        assertEquals(100, body.get("max_output_tokens").asInt)
        assertFalse("the conversation is resent in full, so there is nothing to store", body.get("store").asBoolean)

        val item = body.getAsJsonArray("input")[0].asJsonObject
        assertEquals("user", item.get("role").asString)
        assertEquals("input_text", item.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
    }

    /** An assistant turn replayed as `input_text` is rejected, and a user turn as `output_text` too. */
    @Test
    fun `replays an assistant turn as output_text`() {
        val body = OpenAiProtocol.responsesRequest(
            "gpt-5", 100, null,
            listOf(user("hi"), ChatMessage("assistant", JsonArray().apply { add(textBlock("hello")) })),
            emptyList(),
        )

        val item = body.getAsJsonArray("input")[1].asJsonObject
        assertEquals("assistant", item.get("role").asString)
        assertEquals("output_text", item.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun `writes calls and their answers as their own input items`() {
        val body = OpenAiProtocol.responsesRequest(
            "gpt-5", 100, null,
            listOf(
                assistantToolUse("call_1", "read_project_file", """{"path":"a.kt"}"""),
                ChatMessage("user", JsonArray().apply { add(toolResultBlock("call_1", "contents")) }),
            ),
            emptyList(),
        )

        val input = body.getAsJsonArray("input")
        assertEquals("function_call", input[0].asJsonObject.get("type").asString)
        assertEquals("call_1", input[0].asJsonObject.get("call_id").asString)
        assertEquals("function_call_output", input[1].asJsonObject.get("type").asString)
        assertEquals("call_1", input[1].asJsonObject.get("call_id").asString)
        assertEquals("contents", input[1].asJsonObject.get("output").asString)
    }

    @Test
    fun `declares a tool flat rather than nested`() {
        val body = OpenAiProtocol.responsesRequest("gpt-5", 100, null, listOf(user("hi")), listOf(tool))

        val declared = body.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("function", declared.get("type").asString)
        assertEquals("read_project_file", declared.get("name").asString)
        assertFalse(declared.has("function"))
    }

    // --- Responses: responses -----------------------------------------------------------------------

    @Test
    fun `reads output items into text and tool use`() {
        val turn = OpenAiProtocol.parseResponses(json("""
            {"status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"looking"}]},
              {"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_project_file","arguments":"{\"path\":\"a.kt\"}"}
            ]}
        """))

        assertEquals(2, turn.content.size())
        assertEquals("looking", turn.content[0].asJsonObject.get("text").asString)
        // The item's own id is a different string, and a result quoting it is not recognised.
        assertEquals("call_1", turn.content[1].asJsonObject.get("id").asString)
        assertEquals("a.kt", turn.content[1].asJsonObject.getAsJsonObject("input").get("path").asString)
        assertEquals("tool_use", turn.stopReason)
    }

    @Test
    fun `maps an incomplete response onto max_tokens`() {
        val turn = OpenAiProtocol.parseResponses(json("""
            {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
             "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"half a sen"}]}]}
        """))

        assertEquals("max_tokens", turn.stopReason)
    }

    @Test
    fun `survives incomplete_details being JSON null on a finished response`() {
        val turn = OpenAiProtocol.parseResponses(json("""
            {"status":"completed","incomplete_details":null,
             "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}],
             "usage":{"input_tokens":9,"output_tokens":3,"input_tokens_details":{"cached_tokens":8}}}
        """))

        assertEquals("end_turn", turn.stopReason)
        assertEquals(9, turn.usage?.input_tokens)
        assertEquals(8, turn.usage?.cache_read_input_tokens)
    }

    @Test
    fun `reports a response with no output rather than returning nothing`() {
        val failure = runCatching { OpenAiProtocol.parseResponses(json("""{"status":"completed"}""")) }
        assertTrue(failure.exceptionOrNull() is AnthropicApiException)
    }

    // --- round trip ---------------------------------------------------------------------------------

    /**
     * The agent answers a `tool_use` by its id, so an id that does not survive the trip out and back
     * leaves every result unmatched -- which the provider rejects, permanently, for that chat.
     */
    @Test
    fun `a call id parsed out of a response comes back on the answer`() {
        val turn = OpenAiProtocol.parseChatCompletions(json("""
            {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
              {"id":"call_xyz","type":"function","function":{"name":"read_project_file","arguments":"{}"}}
            ]}}]}
        """))

        val history = listOf(
            ChatMessage("assistant", turn.content),
            ChatMessage("user", JsonArray().apply { add(toolResultBlock("call_xyz", "ok")) }),
        )
        val body = OpenAiProtocol.chatCompletionsRequest("gpt-5", 100, null, history, emptyList())

        val messages = body.getAsJsonArray("messages")
        assertEquals(
            "call_xyz",
            messages[0].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString,
        )
        assertEquals("call_xyz", messages[1].asJsonObject.get("tool_call_id").asString)
        assertNull(messages[1].asJsonObject.get("id"))
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun json(raw: String): JsonObject = JsonParser.parseString(raw.trimIndent()).asJsonObject

    private fun user(text: String) = ChatMessage.text("user", text)

    private fun assistantToolUse(id: String, name: String, arguments: String) =
        ChatMessage("assistant", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", id)
                addProperty("name", name)
                add("input", JsonParser.parseString(arguments).asJsonObject)
            })
        })

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    private fun toolResultBlock(id: String, text: String) = JsonObject().apply {
        addProperty("type", "tool_result")
        addProperty("tool_use_id", id)
        addProperty("content", text)
    }
}
