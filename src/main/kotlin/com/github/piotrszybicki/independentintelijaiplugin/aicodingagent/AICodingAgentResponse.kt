package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.google.gson.JsonArray

internal data class AICodingAgentResponse(
    val content: JsonArray?,
    val stop_reason: String?,
    val usage: AICodingAgentUsage?,
)
