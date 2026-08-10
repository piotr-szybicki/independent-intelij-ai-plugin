package com.github.piotrszybicki.independentintelijaiplugin.settings

/**
 * Which HTTP API the configured endpoint actually speaks.
 *
 * The auth header was never the whole story. [AuthScheme] is enough to reach a gateway that puts the
 * Messages API behind a different token header, but a provider that speaks OpenAI's shape rejects
 * the request body itself -- Foundry answers a Messages-API request with "Unsupported parameter:
 * 'messages'. In the Responses API, this parameter has moved to 'input'", which no header can fix.
 * So the body has to be translated too, and this is what says which way.
 *
 * The two OpenAI entries are a real split rather than one "OpenAI" option: they are different APIs
 * with different request shapes, hosted at different paths, and a deployment serves one or the
 * other. Chat Completions is what nearly everything OpenAI-compatible speaks -- Azure, OpenRouter,
 * vLLM, Ollama, LM Studio; Responses is the newer one OpenAI and Microsoft Foundry have moved to.
 */
enum class WireProtocol(val displayName: String, val pathSuffix: String) {
    ANTHROPIC_MESSAGES("Anthropic Messages (Anthropic, Bedrock/Vertex gateways)", "/messages"),
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions (Azure, OpenRouter, vLLM, Ollama)", "/chat/completions"),
    OPENAI_RESPONSES("OpenAI Responses (OpenAI, Microsoft Foundry)", "/responses"),
    ;

    companion object {

        /**
         * The protocol a URL's path implies, or null when it implies none.
         *
         * Only used to catch a URL pointed at one protocol while the setting says another, which is
         * a 400 every time and an obscure one to read. Deliberately silent on anything unfamiliar:
         * a gateway is free to serve the Messages API from any path it likes, and refusing to send
         * because the path was not recognised would break setups that work today.
         */
        fun impliedBy(url: String): WireProtocol? {
            val path = url.substringBefore('?').trimEnd('/').lowercase()
            // Responses first: "/chat/completions" cannot be confused with it, but checking the
            // longest, most specific suffix first is what keeps that true if paths are ever added.
            return entries.firstOrNull { path.endsWith(it.pathSuffix) }
        }
    }
}
