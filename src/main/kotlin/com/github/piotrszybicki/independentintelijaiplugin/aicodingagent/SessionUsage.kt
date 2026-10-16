package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

data class SessionUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val requests: Int = 0,
) {
    val totalInputTokens: Int get() = inputTokens + cacheWriteTokens + cacheReadTokens

    val cacheHitRate: Double?
        get() = totalInputTokens.takeIf { it > 0 }?.let { cacheReadTokens.toDouble() / it }

    val isEmpty: Boolean get() = requests == 0

    operator fun plus(usage: AICodingAgentUsage): SessionUsage =
        SessionUsage(
            inputTokens = inputTokens + usage.input_tokens,
            outputTokens = outputTokens + usage.output_tokens,
            cacheWriteTokens = cacheWriteTokens + usage.cache_creation_input_tokens,
            cacheReadTokens = cacheReadTokens + usage.cache_read_input_tokens,
            requests = requests + 1,
        )
}
