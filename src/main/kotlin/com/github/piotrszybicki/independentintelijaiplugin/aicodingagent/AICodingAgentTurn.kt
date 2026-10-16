package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray

data class AICodingAgentTurn(
    val content: JsonArray,
    val stopReason: String?,
    val usage: AICodingAgentUsage? = null,
    val requestId: String = "",
)
