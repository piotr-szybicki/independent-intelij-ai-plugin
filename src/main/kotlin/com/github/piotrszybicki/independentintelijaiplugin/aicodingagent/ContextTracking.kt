package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

interface ContextTracking {
    fun observe(
        promptTokens: Int,
        outputTokens: Int,
        promptChars: Int,
        coveredMessages: Int,
    )

    fun estimate(
        history: List<ChatMessage>,
        overheadChars: Int,
    ): Int

    fun invalidateAnchor()

    fun reset()
}
