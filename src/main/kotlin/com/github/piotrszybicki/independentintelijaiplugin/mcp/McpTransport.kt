package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject

/** Raised when a server cannot be reached, refuses a request, or answers with something unusable. */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * How JSON-RPC messages get to a server and back.
 *
 * The protocol above this is identical for a local process and a remote endpoint, so this is the
 * only place the two differ: one writes newline-delimited JSON to a pipe, the other POSTs it.
 */
interface McpTransport : AutoCloseable {

    /** Sends a request and blocks until the response with a matching id arrives, or the wait runs out. */
    fun request(message: JsonObject, timeoutMillis: Long): JsonObject

    /** Sends a notification. These have no id and no reply, so there is nothing to wait for. */
    fun notify(message: JsonObject)
}

/** Builds and reads the JSON-RPC 2.0 envelopes both transports carry. */
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

    /** The `result` of a response, or an exception carrying whatever the server said went wrong. */
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
