package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject

interface McpTransport : AutoCloseable {
    fun request(
        message: JsonObject,
        timeoutMillis: Long,
    ): JsonObject

    fun notify(message: JsonObject)
}
