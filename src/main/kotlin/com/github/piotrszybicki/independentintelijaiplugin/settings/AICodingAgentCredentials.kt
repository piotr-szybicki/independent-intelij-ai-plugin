package com.github.piotrszybicki.independentintelijaiplugin.settings

object AICodingAgentCredentials {

    const val ENV_VAR = "AI_API_KEY"

    val apiKey: String?
        get() = System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }
}
