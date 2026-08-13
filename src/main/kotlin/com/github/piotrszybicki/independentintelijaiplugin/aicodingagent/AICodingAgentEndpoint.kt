package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentCredentials
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
import com.github.piotrszybicki.independentintelijaiplugin.settings.EndpointUrl
import com.github.piotrszybicki.independentintelijaiplugin.settings.WireProtocol
import java.net.URI

/**
 * Where requests go, what they speak and how they authenticate -- everything about the transport
 * that the user can configure, resolved once per turn and carried down to [AICodingAgentClient].
 *
 * Exists so that pointing the plugin at a gateway or another provider does not mean threading five
 * more arguments through the agent loop.
 */
data class AICodingAgentEndpoint(
    val url: String,
    val token: String,
    val authScheme: AuthScheme,
    val protocol: WireProtocol,
    val apiVersion: String,
    val extraHeaders: Map<String, String>,
) {

    /** The headers to send, with the token's header last so a stray extra header cannot shadow it. */
    fun headers(): Map<String, String> = buildMap {
        put("content-type", "application/json")
        // Only Anthropic's own API knows this header; OpenAI's rejects nothing but ignores it, and
        // some gateways in front of it are stricter than that. Send it where it means something.
        if (protocol == WireProtocol.ANTHROPIC_MESSAGES && apiVersion.isNotBlank()) {
            put("anthropic-version", apiVersion)
        }
        putAll(extraHeaders)
        put(authScheme.headerName, authScheme.headerValue(token))
    }

    /** A human-readable reason the endpoint cannot be used, or null when it looks usable. */
    fun validate(): String? {
        if (token.isBlank()) {
            return "no API token is configured -- set the ${AICodingAgentCredentials.ENV_VAR} " +
                "environment variable and restart the IDE"
        }
        if (url.isBlank()) return "no endpoint URL is configured"
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return "the endpoint URL is not a valid URL: $url"
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            return "the endpoint URL must start with http:// or https:// -- got: $url"
        }
        if (uri.host.isNullOrBlank()) return "the endpoint URL has no host: $url"

        // Caught here rather than left to the provider, because the provider's answer to it is a
        // 400 about a parameter that "has moved", which reads like a bug in the request rather than
        // a setting one dropdown away.
        val implied = WireProtocol.impliedBy(uri.path.orEmpty())
        if (implied != null && implied != protocol) {
            return "the endpoint URL looks like a ${implied.displayName.substringBefore(" (")} " +
                "endpoint, but the API protocol is set to ${protocol.displayName.substringBefore(" (")}" +
                " -- change one of the two in Settings | Tools | AICodingAgent"
        }
        return null
    }

    companion object {

        /**
         * Reads the configured endpoint, including the two things that come from outside the
         * settings file: the token, and -- when [EndpointUrl.ENV_VAR] is set or this session has
         * been pointed elsewhere -- the URL.
         */
        fun fromSettings(): AICodingAgentEndpoint {
            val settings = AICodingAgentSettingsState.getInstance().state
            return AICodingAgentEndpoint(
                url = EndpointUrl.resolve(),
                token = AICodingAgentCredentials.apiKey.orEmpty(),
                authScheme = settings.authScheme,
                protocol = settings.wireProtocol,
                apiVersion = settings.apiVersion.trim(),
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
