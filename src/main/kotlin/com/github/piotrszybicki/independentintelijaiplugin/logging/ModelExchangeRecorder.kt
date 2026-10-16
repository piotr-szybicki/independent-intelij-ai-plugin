package com.github.piotrszybicki.independentintelijaiplugin.logging

interface ModelExchangeRecorder {
    fun recordRequest(
        conversationId: String,
        requestId: String,
        protocol: String,
        url: String,
        model: String,
        body: String,
    )

    fun recordResponse(
        conversationId: String,
        requestId: String,
        responseId: String,
        statusCode: Int,
        durationMillis: Long,
        body: String,
    )

    fun recordFailure(
        conversationId: String,
        requestId: String,
        durationMillis: Long,
        error: String,
    )

    fun recordUsage(
        conversationId: String,
        requestId: String,
        model: String,
        inputTokens: Int,
        cacheWriteTokens: Int,
        cacheReadTokens: Int,
        outputTokens: Int,
    )
}
