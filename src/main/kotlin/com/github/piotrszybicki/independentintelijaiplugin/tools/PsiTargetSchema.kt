package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object PsiTargetSchema {
    fun create(
        symbolDescription: String,
        pathDescription: String =
            "File path relative to the project root, at a line where the symbol is declared or used",
        lineDescription: String = "1-based line number of the declaration or the use",
        includeOccurrence: Boolean = false,
        extraProperties: Map<String, Pair<String, String>> = emptyMap(),
        extraRequired: List<String> = emptyList(),
    ): JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add("path", property("string", pathDescription))
                    add("line", property("integer", lineDescription))
                    add("symbol", property("string", symbolDescription))
                    if (includeOccurrence) {
                        add(
                            "occurrence",
                            property(
                                "integer",
                                "Which appearance of the name on that line to use, 1-based. Defaults to 1.",
                            ),
                        )
                    }
                    extraProperties.forEach { (name, specification) ->
                        add(name, property(specification.first, specification.second))
                    }
                },
            )
            add(
                "required",
                JsonArray().apply {
                    listOf("path", "line", "symbol").plus(extraRequired).forEach(::add)
                },
            )
        }

    private fun property(
        type: String,
        description: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("description", description)
        }
}
