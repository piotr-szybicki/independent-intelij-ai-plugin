package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot

/**
 * Which header carries the token.
 *
 * Providers differ only in the header name, not in the request body, so this is enough to reach any
 * endpoint that speaks the Messages API -- Anthropic's own, a corporate gateway in front of it, or
 * Microsoft Foundry. Keeping it an enum rather than a free-text header name is what keeps the token
 * out of here: the secret comes from the environment and is never part of the settings XML.
 */
enum class AuthScheme(val headerName: String, val displayName: String) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)"),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)"),
    API_KEY("api-key", "api-key (Microsoft Foundry)");

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token
}

@Service(Service.Level.APP)
@State(name = "AnthropicChatSettings", storages = [Storage("anthropicChatSettings.xml")])
class AnthropicSettingsState : PersistentStateComponent<AnthropicSettingsState.State> {

    data class State(
        var model: String = "claude-sonnet-5",
        /**
         * The starting cap on a single reply. A starting point rather than a fixed one: saying yes
         * to continuing a cut-off reply doubles it for the rest of that chat, so a conversation that
         * needs longer answers finds its own level without the user editing this.
         */
        var maxTokens: Int = 3000,

        /**
         * How many request/tool-call rounds one message gets before the agent stops and asks whether
         * to carry on. A stop, not a ceiling -- saying yes buys another run of that many -- so this
         * is really "how long before I get asked", and it is only here to catch a loop that has run
         * away with itself.
         */
        var maxIterations: Int = 10,

        /**
         * The full URL of the messages endpoint, path included, rather than a base URL with the path
         * appended: provider paths do not agree (Foundry's base already ends in `/v1`, gateways add
         * prefixes of their own), so guessing at it would break more setups than it saves typing on.
         */
        var endpointUrl: String = DEFAULT_ENDPOINT_URL,
        var authScheme: AuthScheme = AuthScheme.X_API_KEY,
        var anthropicVersion: String = DEFAULT_ANTHROPIC_VERSION,

        /**
         * Extra headers as `Name: Value`, one per line. Stored in plain XML, so this is for routing
         * and tenancy headers a gateway needs -- never for secrets, which belong in the token field.
         */
        var extraHeaders: String = "",

        /**
         * MCP servers, in the `mcpServers` JSON every other MCP client uses, so an entry can be
         * pasted in from a server's own README. Kept as text rather than a structured list because
         * that is the form it arrives in, and parsing it is [com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig]'s job.
         *
         * Stored in plain XML like everything else here, which is why values support
         * `${env:NAME}` -- a server's token belongs in the environment, not in this field.
         */
        var mcpServers: String = "",

        /** Whether each MCP tool call is shown for approval, as shell commands are. */
        var confirmMcpToolCalls: Boolean = true,

        /**
         * Directories to look for skills in, one path per line. Relative paths are resolved against
         * the project root; absolute ones are taken as they are, so a root can live anywhere --
         * which is the point, since the skills worth carrying between projects are not in any of
         * them. Parsing is [com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot]'s job.
         */
        var skillPaths: String = SkillRoot.DEFAULT_PATHS,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

        fun getInstance(): AnthropicSettingsState = service()
    }
}
