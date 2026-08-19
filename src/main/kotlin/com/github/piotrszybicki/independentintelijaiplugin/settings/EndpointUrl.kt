package com.github.piotrszybicki.independentintelijaiplugin.settings

object EndpointUrl {

    const val ENV_VAR = "AI_API_URL"

    val fromEnvironment: String?
        get() = System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }

    fun resolve(configured: String): String = resolve(fromEnvironment, configured)

    fun resolve(environment: String?, configured: String): String =
        environment?.trim()?.takeIf { it.isNotBlank() }
            ?: configured.trim().ifBlank { AgentConfiguration.DEFAULT_ENDPOINT_URL }
}
