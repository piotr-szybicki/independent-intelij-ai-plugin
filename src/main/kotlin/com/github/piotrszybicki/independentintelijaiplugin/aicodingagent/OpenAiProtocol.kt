package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Translates between the conversation as the plugin holds it and the two OpenAI wire formats.
 *
 * The plugin's own representation stays Anthropic-shaped everywhere else -- [ChatMessage] with
 * `text` / `tool_use` / `tool_result` blocks, which is what the agent loop, the transcript and the
 * saved history all read. Translating at the HTTP boundary rather than introducing a third, neutral
 * shape is much the smaller change, and it keeps chats saved before this existed readable.
 *
 * The translation is lossy in one direction on purpose: reasoning is dropped. Both APIs return it,
 * neither requires it echoed back for a conversation the server does not store, and the plugin has
 * nowhere to show it -- so it is read past rather than kept and resent.
 *
 * Everything here reads defensively. "OpenAI-compatible" covers a lot of servers that agree on the
 * happy path and differ on the details -- a field sent as JSON null instead of omitted, arguments
 * sent as an object instead of a string -- and a response that cannot be parsed costs the turn.
 */
internal object OpenAiProtocol {

    // --- Chat Completions ---------------------------------------------------------------------------

    /**
     * A `/chat/completions` request body.
     *
     * `max_completion_tokens` rather than `max_tokens`: the reasoning models reject the older field
     * outright ("Unsupported parameter: 'max_tokens' is not supported with this model"), while the
     * servers that only understand `max_tokens` ignore an unknown field rather than failing on it.
     * So the new name costs an uncapped reply on an old server, and the old one costs the whole
     * request on a current model.
     */
    fun chatCompletionsRequest(
        model: String,
        maxTokens: Int,
        system: String?,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        cacheKey: String? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("max_completion_tokens", maxTokens)
        addCacheKey(cacheKey)

        val out = JsonArray()
        if (!system.isNullOrEmpty()) {
            // "system" rather than "developer": the newer models accept both and map one onto the
            // other, while everything older and everything self-hosted only knows this one.
            out.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", system)
            })
        }

        for (message in messages) {
            val parts = split(message)

            // Results first and prose after, whatever order the blocks were in: a `tool` message has
            // to follow the assistant turn that asked for it, and anything the user typed alongside
            // belongs after the answers rather than in among them.
            for (result in parts.results) {
                out.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", result.id)
                    addProperty("content", result.text)
                })
            }

            if (message.role == "assistant") {
                if (parts.text.isEmpty() && parts.calls.isEmpty()) continue
                out.add(JsonObject().apply {
                    // Explicitly null rather than absent when the turn was nothing but tool calls:
                    // the API allows either, but some compatible servers insist the key is there.
                    addProperty("role", "assistant")
                    add("content", if (parts.text.isEmpty()) JsonNull.INSTANCE else JsonPrimitive(parts.text))
                    if (parts.calls.isNotEmpty()) {
                        add("tool_calls", JsonArray().apply {
                            parts.calls.forEach { call ->
                                add(JsonObject().apply {
                                    addProperty("id", call.id)
                                    addProperty("type", "function")
                                    add("function", JsonObject().apply {
                                        addProperty("name", call.name)
                                        addProperty("arguments", call.arguments.toString())
                                    })
                                })
                            }
                        })
                    }
                })
            } else if (parts.text.isNotEmpty()) {
                out.add(JsonObject().apply {
                    addProperty("role", message.role)
                    addProperty("content", parts.text)
                })
            }
        }
        add("messages", out)

        if (tools.isNotEmpty()) {
            add("tools", JsonArray().apply {
                tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)
                            add("parameters", tool.input_schema)
                        })
                    })
                }
            })
        }
    }

    /** Reads a `/chat/completions` response back into the plugin's own block shape. */
    fun parseChatCompletions(root: JsonObject): AICodingAgentTurn {
        val choice = root.array("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw AICodingAgentApiException("The response has no choices: $root")
        val message = choice.obj("message")
            ?: throw AICodingAgentApiException("The response's first choice has no message: $root")

        val content = JsonArray()
        messageText(message.get("content"))?.let { content.add(textBlock(it)) }
        message.array("tool_calls")?.forEach { call ->
            if (!call.isJsonObject) return@forEach
            val obj = call.asJsonObject
            val function = obj.obj("function") ?: return@forEach
            content.add(
                toolUseBlock(
                    id = obj.string("id"),
                    name = function.string("name"),
                    arguments = function.get("arguments"),
                )
            )
        }

        val usage = root.obj("usage")
        return AICodingAgentTurn(
            content,
            stopReason(choice.string("finish_reason").takeIf { it.isNotEmpty() }, content),
            usage?.let {
                uncached(
                    total = it.int("prompt_tokens"),
                    cached = it.obj("prompt_tokens_details").int("cached_tokens"),
                    output = it.int("completion_tokens"),
                )
            },
        )
    }

    // --- Responses ----------------------------------------------------------------------------------

    /**
     * A `/responses` request body.
     *
     * [reasoning] is the one field here that has no equivalent on Chat Completions in this plugin --
     * see [ReasoningOptions.reasoningJson] for why it is sent on this protocol only. Null leaves it
     * out entirely, which is not the same as sending a level: an absent field is the deployment's
     * own default, and that default can be no reasoning at all.
     */
    fun responsesRequest(
        model: String,
        maxTokens: Int,
        system: String?,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        cacheKey: String? = null,
        reasoning: JsonObject? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("max_output_tokens", maxTokens)
        addCacheKey(cacheKey)
        if (!system.isNullOrEmpty()) addProperty("instructions", system)
        if (reasoning != null) add("reasoning", reasoning)
        // The plugin resends the whole conversation on every request, so a server-side copy buys it
        // nothing -- and this endpoint keeps one by default, which would leave the user's source
        // sitting on the provider for no reason.
        addProperty("store", false)

        val input = JsonArray()
        for (message in messages) {
            val parts = split(message)

            for (result in parts.results) {
                input.add(JsonObject().apply {
                    addProperty("type", "function_call_output")
                    addProperty("call_id", result.id)
                    addProperty("output", result.text)
                })
            }

            if (parts.text.isNotEmpty()) {
                // The part type depends on who is speaking: an assistant turn replayed as
                // `input_text` is rejected, and so is a user turn sent as `output_text`.
                val partType = if (message.role == "assistant") "output_text" else "input_text"
                input.add(JsonObject().apply {
                    addProperty("role", message.role)
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", partType)
                            addProperty("text", parts.text)
                        })
                    })
                })
            }

            for (call in parts.calls) {
                input.add(JsonObject().apply {
                    addProperty("type", "function_call")
                    addProperty("call_id", call.id)
                    addProperty("name", call.name)
                    addProperty("arguments", call.arguments.toString())
                })
            }
        }
        add("input", input)

        if (tools.isNotEmpty()) {
            // Flat, unlike Chat Completions' nested `function` object -- same fields, one level up.
            add("tools", JsonArray().apply {
                tools.forEach { tool ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)
                        add("parameters", tool.input_schema)
                    })
                }
            })
        }
    }

    /** Reads a `/responses` response back into the plugin's own block shape. */
    fun parseResponses(root: JsonObject): AICodingAgentTurn {
        val output = root.array("output")
            ?: throw AICodingAgentApiException("The response has no output: $root")

        val content = JsonArray()
        for (item in output) {
            if (!item.isJsonObject) continue
            val obj = item.asJsonObject
            when (obj.string("type")) {
                "message" -> obj.array("content")?.forEach { part ->
                    if (!part.isJsonObject) return@forEach
                    val partObj = part.asJsonObject
                    if (partObj.string("type") != "output_text") return@forEach
                    textOf(partObj.get("text"))?.let { content.add(textBlock(it)) }
                }

                "function_call" -> content.add(
                    toolUseBlock(
                        // `call_id` is what an answer has to quote; the item's own `id` is a
                        // different string and a result carrying it is not recognised.
                        id = obj.string("call_id").ifEmpty { obj.string("id") },
                        name = obj.string("name"),
                        arguments = obj.get("arguments"),
                    )
                )
                // "reasoning" and anything else: read past, see the class comment.
            }
        }

        // Truncation is a status on the whole response here rather than a reason on a choice.
        val truncated = root.string("status") == "incomplete" &&
            root.obj("incomplete_details").string("reason") == "max_output_tokens"

        val usage = root.obj("usage")
        return AICodingAgentTurn(
            content,
            if (truncated) "max_tokens" else stopReason(null, content),
            usage?.let {
                uncached(
                    total = it.int("input_tokens"),
                    cached = it.obj("input_tokens_details").int("cached_tokens"),
                    output = it.int("output_tokens"),
                )
            },
        )
    }

    // --- shared -------------------------------------------------------------------------------------

    /**
     * Names the conversation a request belongs to, so its cached prefix can be found again.
     *
     * Caching here is nothing like Anthropic's: there is no breakpoint to place and the prefix is
     * matched automatically. All this field changes is where the request is routed -- which is
     * nevertheless what decides whether anything is matched, because an entry only exists on the
     * machine that wrote it. Routing without a key goes by a hash of the prefix alone, so every
     * conversation this plugin opens hashes alike -- one system prompt, one set of tool schemas --
     * and they compete for a single machine's cache, while load can move one chat off its own entry
     * between turns. The key is mixed into that hash, giving each chat a lane of its own.
     *
     * What hangs on it is the whole stable prefix -- the tool schemas and the system prompt, which
     * is the bulk of every request and goes out again on every iteration of every turn.
     *
     * The chat's own id, which is a random UUID. It is sent to the provider and shows up in their
     * logs, so it must be opaque: nothing the user typed, and nothing identifying them. Left out
     * when blank rather than sent as a placeholder, since one shared constant would put every
     * conversation back on a single machine -- worse than no key at all.
     *
     * It is a field of both OpenAI request schemas rather than something invented here, so the
     * providers that do not implement it drop it the way they drop `max_completion_tokens` on an
     * older deployment. A compatible server strict enough to reject unknown fields outright would
     * reject this one -- the same bet `store` already makes on the Responses shape.
     */
    private fun JsonObject.addCacheKey(cacheKey: String?) {
        if (!cacheKey.isNullOrBlank()) addProperty("prompt_cache_key", cacheKey)
    }

    /**
     * Usage in the plugin's own shape, where the input figures do not overlap.
     *
     * The two APIs count on different bases. Anthropic reports what it charged full price for and
     * counts the cached tokens separately, so its three input numbers add up to the whole prompt.
     * Both OpenAI shapes report the *whole* prompt as the input count and then say how much of it
     * came from the cache -- so `cached` is already inside `total`, and passing the two through
     * unchanged makes [SessionUsage.totalInputTokens] count every cached token twice and halves the
     * hit rate it reports. Subtracting here rather than teaching the totals about protocols keeps
     * that arithmetic in one place.
     *
     * There is no cache-write figure to record. The models that cache implicitly do not charge for
     * the write and do not report one; the newer ones that do report `cache_write_tokens` are also
     * the ones that need explicit breakpoints, which nothing here sends.
     *
     * Floored at zero for the compatible servers that report the two disjointly after all. Getting
     * that wrong understates the input; letting it go negative corrupts every total downstream.
     */
    private fun uncached(total: Int, cached: Int, output: Int) = AICodingAgentUsage(
        input_tokens = (total - cached).coerceAtLeast(0),
        output_tokens = output,
        cache_read_input_tokens = cached,
    )

    private class ToolCall(val id: String, val name: String, val arguments: JsonObject)

    private class ToolResult(val id: String, val text: String)

    /**
     * One of the plugin's messages taken apart into the three things both OpenAI formats keep
     * separate: prose, calls, and answers to calls.
     *
     * Both formats want one string of prose per message where the plugin can hold several text
     * blocks, so the text is joined rather than carried block by block.
     */
    private class Parts(val text: String, val calls: List<ToolCall>, val results: List<ToolResult>)

    private fun split(message: ChatMessage): Parts {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        val results = mutableListOf<ToolResult>()

        for (block in message.content) {
            if (!block.isJsonObject) continue
            val obj = block.asJsonObject
            when (obj.string("type")) {
                "text" -> textOf(obj.get("text"))?.let {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(it)
                }

                "tool_use" -> calls.add(
                    ToolCall(obj.string("id"), obj.string("name"), obj.obj("input") ?: JsonObject())
                )

                "tool_result" -> results.add(ToolResult(obj.string("tool_use_id"), resultText(obj)))
                // "thinking" and "redacted_thinking" are Anthropic's own, and only turn up here in a
                // chat that started against Anthropic and was carried on against something else.
            }
        }
        return Parts(text.toString(), calls, results)
    }

    /**
     * A tool result's text.
     *
     * The plugin only ever writes a string, but a history saved by another client -- or a shape the
     * API grows later -- can hold the block-array form, so both are read.
     */
    private fun resultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        return when {
            content.isJsonNull -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("\n") { part ->
                if (part.isJsonObject) textOf(part.asJsonObject.get("text")).orEmpty() else part.toString()
            }

            else -> content.toString()
        }
    }

    /**
     * An assistant message's prose.
     *
     * Normally a plain string, but the multimodal servers send the same array of typed parts the
     * request uses, so that form is read too rather than coming back as a blank reply.
     */
    private fun messageText(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonArray) {
            return element.asJsonArray
                .mapNotNull { part -> if (part.isJsonObject) textOf(part.asJsonObject.get("text")) else null }
                .joinToString("\n")
                .ifEmpty { null }
        }
        return textOf(element)
    }

    /**
     * The stop reason in the plugin's vocabulary.
     *
     * Only `max_tokens` actually drives anything -- it is what makes the agent offer to continue a
     * cut-off reply -- but the rest is mapped anyway so that a logged reason reads the same
     * whichever provider produced it.
     */
    private fun stopReason(finishReason: String?, content: JsonArray): String = when (finishReason) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "refusal"
        // Responses has no per-choice reason at all, and a Chat Completions "stop" alongside tool
        // calls is what the looser servers report. The content is the better witness in both cases.
        else -> if (content.any { it.isJsonObject && it.asJsonObject.string("type") == "tool_use" }) {
            "tool_use"
        } else {
            finishReason ?: "end_turn"
        }
    }

    private fun toolUseBlock(id: String, name: String, arguments: JsonElement?): JsonObject =
        JsonObject().apply {
            addProperty("type", "tool_use")
            addProperty("id", id)
            addProperty("name", name)
            add("input", parseArguments(arguments))
        }

    private fun textBlock(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    /**
     * A tool call's arguments as an object.
     *
     * Both APIs send them as a JSON string rather than as JSON, and a reply cut off by the token
     * limit leaves that string ending mid-object. An empty object rather than a thrown exception
     * then: the tool reports its missing arguments in a result the model can act on, where a failed
     * turn would take the whole conversation with it.
     */
    private fun parseArguments(element: JsonElement?): JsonObject {
        if (element == null || element.isJsonNull) return JsonObject()
        // Some compatible servers send the object itself rather than a string of it.
        if (element.isJsonObject) return element.asJsonObject
        val raw = runCatching { element.asString }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return JsonObject()
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { JsonObject() }
    }

    /** The element as a non-empty string, or null -- covering absent, JSON null and non-string alike. */
    private fun textOf(element: JsonElement?): String? = element
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.takeIf { it.isNotEmpty() }

    // Gson's own getAsJsonObject/getAsJsonArray throw a ClassCastException on a member that is JSON
    // null, which is exactly how several of these fields arrive when they are empty.
    private fun JsonObject?.obj(name: String): JsonObject? =
        this?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.array(name: String): JsonArray? =
        this?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject?.string(name: String): String =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject?.int(name: String): Int =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: 0
}
