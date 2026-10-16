package com.github.piotrszybicki.independentintelijaiplugin.settings

enum class AuthScheme(
    val headerName: String,
    val displayName: String,
    val aliases: List<String>,
) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)", listOf("anthropic")),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)", listOf("bearer", "oauth")),
    API_KEY("api-key", "api-key (Microsoft Foundry)", listOf("foundry")),
    ;

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token

    companion object {
        fun parse(value: String): AuthScheme? {
            val wanted =
                value
                    .trim()
                    .lowercase()
                    .substringBefore(':')
                    .trim()
            return entries.firstOrNull { scheme ->
                wanted == scheme.headerName.lowercase() ||
                    wanted == scheme.name.lowercase() ||
                    wanted in scheme.aliases
            }
        }
    }
}
