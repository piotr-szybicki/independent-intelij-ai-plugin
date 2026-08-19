package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonObject

interface AICodingAgentTool {
    val name: String
    val description: String

    val inputSchema: JsonObject

    val interruptible: Boolean get() = true

    fun execute(input: JsonObject): String

    fun toDefinition() = ToolDefinition(name, description, inputSchema)
}
