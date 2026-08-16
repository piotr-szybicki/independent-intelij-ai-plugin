package com.github.piotrszybicki.independentintelijaiplugin.settings

import java.net.URI

/**
 * What an endpoint URL says about the provider behind it.
 *
 * Three settings have to agree with each other and with the URL before a request can succeed --
 * [WireProtocol] decides the shape of the body, [AuthScheme] decides which header carries the token,
 * and [ThinkingMode] decides whether a field the endpoint may not know gets sent at all. Getting any
 * of them wrong is a 400 whose message points at the request rather than at the settings page, and
 * the URL already says which combination is right. So it is read rather than asked for.
 *
 * Deliberately narrow: only hosts that are unambiguous fill in [authScheme], and everything else
 * leaves it null so the user's own answer stands. Guessing a token header for a gateway would break
 * setups that work today, and the point of this is to stop the settings page fighting the URL, not
 * to have it overrule the person typing.
 */
data class ProviderProfile(
    /** What to call the provider on the settings page. */
    val displayName: String,
    val protocol: WireProtocol,
    /** Null when the host is not one of the known ones, which leaves the configured header alone. */
    val authScheme: AuthScheme?,
    val thinking: ThinkingMode,
) {

    companion object {

        /**
         * The providers whose token header is a fact rather than a guess. Anything not in here is
         * matched on its path alone, which says the protocol and nothing about authentication.
         */
        private val KNOWN_HOSTS = listOf(
            KnownHost(
                displayName = "the Anthropic API",
                hosts = listOf("anthropic.com"),
                authScheme = AuthScheme.X_API_KEY,
                protocol = WireProtocol.ANTHROPIC_MESSAGES,
            ),
            KnownHost(
                displayName = "Microsoft Foundry",
                hosts = listOf("services.ai.azure.com", "openai.azure.com", "cognitiveservices.azure.com"),
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_RESPONSES,
            ),
            KnownHost(
                displayName = "OpenAI",
                hosts = listOf("openai.com"),
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_RESPONSES,
            ),
        )

        /**
         * The profile [url] implies, or null when it implies nothing worth acting on.
         *
         * The path decides the protocol wherever it says one, because a host can serve more than one
         * API and the path is the only part of the URL that distinguishes them. The host decides the
         * token header, and only for the three providers whose answer is not a guess.
         */
        fun detect(url: String): ProviderProfile? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null

            val known = KNOWN_HOSTS.firstOrNull { it.matches(host) }
            // A recognised host still falls back to its usual API when the path says nothing, which
            // is what makes a half-typed URL settle on something sensible rather than on nothing.
            val protocol = WireProtocol.impliedBy(uri.path.orEmpty()) ?: known?.protocol ?: return null

            return ProviderProfile(
                displayName = known?.displayName ?: protocol.displayName.substringBefore(" ("),
                protocol = protocol,
                authScheme = known?.authScheme,
                thinking = defaultThinking(protocol),
            )
        }

        /**
         * What the thinking setting can honestly be for a protocol, which is what an entry that
         * leaves `thinking` out gets.
         *
         * Tied to the protocol rather than to the provider because it is the request builder, not
         * the endpoint, that decides whether anything is sent: the Messages API carries `thinking`
         * and the Responses API carries `reasoning`, while nothing is sent on Chat Completions at
         * all. Offering "on" there would be a setting that reads as applied and does nothing --
         * see [com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.ReasoningOptions].
         */
        fun defaultThinking(protocol: WireProtocol): ThinkingMode = when (protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> ThinkingMode.ADAPTIVE
            WireProtocol.OPENAI_RESPONSES -> ThinkingMode.ADAPTIVE
            WireProtocol.OPENAI_CHAT_COMPLETIONS -> ThinkingMode.PROVIDER_DEFAULT
        }
    }

    /** A provider whose token header is settled rather than guessed at. */
    private data class KnownHost(
        val displayName: String,
        /** Registrable domains, matched exactly or as a suffix, so a per-resource host counts. */
        val hosts: List<String>,
        val authScheme: AuthScheme,
        val protocol: WireProtocol,
    ) {
        fun matches(host: String): Boolean = hosts.any { host == it || host.endsWith(".$it") }
    }
}
