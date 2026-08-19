package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal object OpenAiProtocol {


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
            out.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", system)
            })
        }

        for (message in messages) {
            val parts = split(message)

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
                        id = obj.string("call_id").ifEmpty { obj.string("id") },
                        name = obj.string("name"),
                        arguments = obj.get("arguments"),
                    )
                )
            }
        }

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


    private fun JsonObject.addCacheKey(cacheKey: String?) {
        if (!cacheKey.isNullOrBlank()) addProperty("prompt_cache_key", cacheKey)
    }

    private fun uncached(total: Int, cached: Int, output: Int) = AICodingAgentUsage(
        input_tokens = (total - cached).coerceAtLeast(0),
        output_tokens = output,
        cache_read_input_tokens = cached,
    )

    private class ToolCall(val id: String, val name: String, val arguments: JsonObject)

    private class ToolResult(val id: String, val text: String)

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
            }
        }
        return Parts(text.toString(), calls, results)
    }

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

    private fun stopReason(finishReason: String?, content: JsonArray): String = when (finishReason) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "refusal"
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

    private fun parseArguments(element: JsonElement?): JsonObject {
        if (element == null || element.isJsonNull) return JsonObject()
        if (element.isJsonObject) return element.asJsonObject
        val raw = runCatching { element.asString }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return JsonObject()
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { JsonObject() }
    }

    private fun textOf(element: JsonElement?): String? = element
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject?.obj(name: String): JsonObject? =
        this?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.array(name: String): JsonArray? =
        this?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject?.string(name: String): String =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject?.int(name: String): Int =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: 0
}
