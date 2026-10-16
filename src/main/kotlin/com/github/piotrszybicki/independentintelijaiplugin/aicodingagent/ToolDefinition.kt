package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val input_schema: JsonObject,
)
