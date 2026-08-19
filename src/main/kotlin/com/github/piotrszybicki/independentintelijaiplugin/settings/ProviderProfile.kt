package com.github.piotrszybicki.independentintelijaiplugin.settings

import java.net.URI

data class ProviderProfile(
    val displayName: String,
    val protocol: WireProtocol,
    val authScheme: AuthScheme?,
    val thinking: ThinkingMode,
) {

    companion object {

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

        fun detect(url: String): ProviderProfile? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null

            val known = KNOWN_HOSTS.firstOrNull { it.matches(host) }
            val protocol = WireProtocol.impliedBy(uri.path.orEmpty()) ?: known?.protocol ?: return null

            return ProviderProfile(
                displayName = known?.displayName ?: protocol.displayName.substringBefore(" ("),
                protocol = protocol,
                authScheme = known?.authScheme,
                thinking = defaultThinking(protocol),
            )
        }

        fun defaultThinking(protocol: WireProtocol): ThinkingMode = when (protocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> ThinkingMode.ADAPTIVE
            WireProtocol.OPENAI_RESPONSES -> ThinkingMode.ADAPTIVE
            WireProtocol.OPENAI_CHAT_COMPLETIONS -> ThinkingMode.PROVIDER_DEFAULT
        }
    }

    private data class KnownHost(
        val displayName: String,
        val hosts: List<String>,
        val authScheme: AuthScheme,
        val protocol: WireProtocol,
    ) {
        fun matches(host: String): Boolean = hosts.any { host == it || host.endsWith(".$it") }
    }
}
