package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

internal object ToolSummary {

    private val prettyJson = GsonBuilder().setPrettyPrinting().create()

    fun summarize(toolInput: JsonObject): String {
        val preferredKeys = listOf("path", "file", "filePath", "name", "newName", "symbol", "query", "text")
        val value = preferredKeys.asSequence()
            .mapNotNull { toolInput.get(it) as? JsonPrimitive }
            .firstOrNull()
            ?: toolInput.entrySet().asSequence().mapNotNull { it.value as? JsonPrimitive }.firstOrNull()
        val summary = value?.asString?.replace('\n', ' ')?.trim().orEmpty()
        return if (summary.length > 70) summary.take(69) + "…" else summary
    }

    fun details(toolInput: JsonObject, result: String): String = buildString {
        append(prettyJson.toJson(toolInput))
        if (result.isNotBlank()) {
            append("\n\n")
            append(truncate(result))
        }
    }

    fun truncate(text: String, limit: Int = 4000): String =
        if (text.length <= limit) text else text.take(limit) + "\n… (${text.length - limit} more characters)"
}
