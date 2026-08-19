package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface McpTransport : AutoCloseable {

    fun request(message: JsonObject, timeoutMillis: Long): JsonObject

    fun notify(message: JsonObject)
}

object McpRpc {

    fun request(id: Int, method: String, params: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", id)
        addProperty("method", method)
        params?.let { add("params", it) }
    }

    fun notification(method: String, params: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("method", method)
        params?.let { add("params", it) }
    }

    fun resultOf(response: JsonObject, method: String): JsonObject {
        response.getAsJsonObject("error")?.let { error ->
            val message = error.get("message")?.asString ?: "no message"
            val code = error.get("code")?.asString ?: "?"
            throw McpException("$method failed: $message (code $code)")
        }
        return response.getAsJsonObject("result")
            ?: throw McpException("$method returned a response with neither a result nor an error")
    }
}
