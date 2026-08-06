package com.github.piotrszybicki.independentintelijaiplugin.anthropic

import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicCredentials
import com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
import java.net.URI

/**
 * Where requests go and how they authenticate -- everything about the transport that the user can
 * configure, resolved once per turn and carried down to [AnthropicClient].
 *
 * Exists so that pointing the plugin at a gateway or another provider does not mean threading four
 * more arguments through the agent loop.
 */
data class AnthropicEndpoint(
    val url: String,
    val token: String,
    val authScheme: AuthScheme,
    val anthropicVersion: String,
    val extraHeaders: Map<String, String>,
) {

    /** The headers to send, with the token's header last so a stray extra header cannot shadow it. */
    fun headers(): Map<String, String> = buildMap {
        put("content-type", "application/json")
        if (anthropicVersion.isNotBlank()) put("anthropic-version", anthropicVersion)
        putAll(extraHeaders)
        put(authScheme.headerName, authScheme.headerValue(token))
    }

    /** A human-readable reason the endpoint cannot be used, or null when it looks usable. */
    fun validate(): String? {
        if (token.isBlank()) return "no API token is configured"
        if (url.isBlank()) return "no endpoint URL is configured"
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return "the endpoint URL is not a valid URL: $url"
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            return "the endpoint URL must start with http:// or https:// -- got: $url"
        }
        if (uri.host.isNullOrBlank()) return "the endpoint URL has no host: $url"
        return null
    }

    companion object {

        /**
         * Reads the configured endpoint, including the token.
         *
         * Touches PasswordSafe, which can block, so this must not be called on the EDT.
         */
        fun fromSettings(): AnthropicEndpoint {
            val settings = AnthropicSettingsState.getInstance().state
            return AnthropicEndpoint(
                url = settings.endpointUrl.trim().ifBlank { AnthropicSettingsState.DEFAULT_ENDPOINT_URL },
                token = AnthropicCredentials.apiKey.orEmpty(),
                authScheme = settings.authScheme,
                anthropicVersion = settings.anthropicVersion.trim(),
                extraHeaders = parseHeaders(settings.extraHeaders),
            )
        }

        /**
         * Parses `Name: Value` lines. Anything unparseable is dropped rather than failing the turn:
         * a typo in an optional routing header should not be the reason a conversation stops.
         */
        fun parseHeaders(raw: String): Map<String, String> = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
    }
}
