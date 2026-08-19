package com.github.piotrszybicki.independentintelijaiplugin.settings

enum class WireProtocol(
    val displayName: String,
    val pathSuffix: String,
    val wireName: String,
    private val aliases: List<String>,
) {
    ANTHROPIC_MESSAGES(
        "Anthropic Messages (Anthropic, Bedrock/Vertex gateways)", "/messages", "anthropic-messages",
        listOf("anthropic", "messages"),
    ),
    OPENAI_CHAT_COMPLETIONS(
        "OpenAI Chat Completions (Azure, OpenRouter, vLLM, Ollama)", "/chat/completions", "openai-chat-completions",
        listOf("chat-completions", "completions"),
    ),
    OPENAI_RESPONSES(
        "OpenAI Responses (OpenAI, Microsoft Foundry)", "/responses", "openai-responses",
        listOf("responses"),
    ),
    ;

    companion object {

        fun parse(value: String): WireProtocol? {
            val wanted = value.trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { protocol ->
                wanted == protocol.wireName ||
                    wanted == protocol.name.lowercase().replace('_', '-') ||
                    wanted in protocol.aliases
            }
        }

        fun impliedBy(url: String): WireProtocol? {
            val path = url.substringBefore('?').trimEnd('/').lowercase()
            // Responses first: "/chat/completions" cannot be confused with it, but checking the
            // longest, most specific suffix first is what keeps that true if paths are ever added.
            return entries.firstOrNull { path.endsWith(it.pathSuffix) }
        }
    }
}
