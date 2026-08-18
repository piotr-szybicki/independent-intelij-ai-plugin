package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AuthScheme
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
    /**
     * How long one request may take before it is given up on, in seconds. See
     * [AgentConfiguration.requestTimeoutSeconds] for why it is per configuration.
     *
     * Defaulted, unlike the transport fields above it: an endpoint built by hand -- a test, a probe
     * -- has an opinion about where it points and none about how long it may take.
     */
    val requestTimeoutSeconds: Int = AgentConfiguration.DEFAULT_REQUEST_TIMEOUT_SECONDS,
    /** Which entry of the configuration file this came from, for naming it in an error. */
    val configurationName: String = "",
    /** The variable the token was meant to come from, when it names one and the name is unset. */
    val tokenEnvVar: String? = null,
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

        /**
         * The transport one [AgentConfiguration] describes, with the one thing it names rather than
         * holds resolved: the token, read from the environment when the field starts with `$`.
         *
         * The URL is the entry's own and nothing overrides it. An environment variable that replaced
         * it would leave the protocol, the token header and the token itself behind, all of which
         * belong to the provider the URL just stopped pointing at -- and the first of those is a
         * refusal from [validate] rather than anything the user could read as an override. The
         * environment still has a say, but only over
         * [com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration.fallback]
         * -- the configuration used when there is no file.
         */
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
