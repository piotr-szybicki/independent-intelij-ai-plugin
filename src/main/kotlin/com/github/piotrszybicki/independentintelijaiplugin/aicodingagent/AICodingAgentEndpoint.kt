package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
import com.github.piotrszybicki.independentintelijaiplugin.settings.WireProtocol
import java.net.URI

data class AICodingAgentEndpoint(
    val url: String,
    val token: String,
    val authScheme: AuthScheme,
    val protocol: WireProtocol,
    val apiVersion: String,
    val extraHeaders: Map<String, String>,
    val requestTimeoutSeconds: Int = AgentConfiguration.DEFAULT_REQUEST_TIMEOUT_SECONDS,
    val configurationName: String = "",
    val tokenEnvVar: String? = null,
) {

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

    fun validate(): String? {
        if (token.isBlank()) {
            val configuration = configurationName.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            return if (tokenEnvVar != null) {
                "the token for configuration$configuration is read from \$$tokenEnvVar, which is " +
                    "empty or undefined in the IDE's environment -- set it and restart the IDE, " +
                    "which only sees the variables it was launched with"
            } else {
                "configuration$configuration has no \"token\" -- put the token in " +
                    "${AgentConfiguration.FILE_NAME}, or write \$NAME there to read it from an " +
                    "environment variable"
            }
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
        // one line of the configuration file.
        val implied = WireProtocol.impliedBy(uri.path.orEmpty())
        if (implied != null && implied != protocol) {
            return "the endpoint URL looks like a ${implied.displayName.substringBefore(" (")} " +
                "endpoint, but the API protocol is set to ${protocol.displayName.substringBefore(" (")}" +
                " -- change the \"url\" or the \"protocol\" of this entry in ${AgentConfiguration.FILE_NAME}"
        }
        return null
    }

    companion object {

        fun from(configuration: AgentConfiguration): AICodingAgentEndpoint = AICodingAgentEndpoint(
            url = configuration.url,
            token = configuration.resolvedToken,
            authScheme = configuration.authScheme,
            protocol = configuration.protocol,
            apiVersion = configuration.apiVersion.trim(),
            extraHeaders = configuration.extraHeaders,
            requestTimeoutSeconds = configuration.requestTimeoutSeconds,
            configurationName = configuration.name,
            tokenEnvVar = configuration.tokenEnvVar,
        )
    }
}
