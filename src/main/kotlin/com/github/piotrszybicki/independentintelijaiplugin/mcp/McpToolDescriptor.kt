package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
