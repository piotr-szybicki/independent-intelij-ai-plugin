package com.github.piotrszybicki.independentintelijaiplugin.mcp

class McpException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
