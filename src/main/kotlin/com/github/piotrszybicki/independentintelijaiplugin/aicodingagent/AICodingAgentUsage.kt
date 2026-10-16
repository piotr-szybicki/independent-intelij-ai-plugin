package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

data class AICodingAgentUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
) {
    val promptTokens: Int
        get() = input_tokens + cache_creation_input_tokens + cache_read_input_tokens
}
