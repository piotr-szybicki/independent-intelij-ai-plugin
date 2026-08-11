package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog

/**
 * Which header carries the token.
 *
 * Half of what it takes to reach a given provider -- [WireProtocol] is the other half, for the ones
 * that differ in the request body too. Keeping it an enum rather than a free-text header name is
 * what keeps the token out of here: the secret comes from the environment and is never part of the
 * settings XML.
 */
enum class AuthScheme(val headerName: String, val displayName: String) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)"),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)"),
    API_KEY("api-key", "api-key (Microsoft Foundry)");

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token
}

@Service(Service.Level.APP)
@State(name = "AICodingAgentChatSettings", storages = [Storage("aiCodingAgentChatSettings.xml")])
class AICodingAgentSettingsState : PersistentStateComponent<AICodingAgentSettingsState.State> {

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

        /**
         * Which API shape the endpoint speaks. Defaults to Anthropic's, which is what every
         * settings file written before this field existed was configured for -- an absent value
         * reads back as the default, so upgrading leaves those setups exactly where they were.
         */
        var wireProtocol: WireProtocol = WireProtocol.ANTHROPIC_MESSAGES,

        /** Ignored unless [wireProtocol] is the Messages API: no other provider knows the header. */
        var apiVersion: String = DEFAULT_API_VERSION,

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
         * Which built-in tools a request carries, by name, comma-separated. Names not in
         * [com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog] are ignored, so a
         * tool that is later renamed or dropped leaves nothing behind to clean up.
         *
         * Stored as the set to send rather than the set to withhold, which is what makes the
         * default mean something: an absent value reads back as [ToolCatalog.DEFAULT_ENABLED], and a
         * tool added to the catalog in a later version stays off until it is asked for. Empty is a
         * real answer -- no built-in tools at all, for a chat that works through MCP servers.
         */
        var enabledTools: String = ToolCatalog.DEFAULT_ENABLED,

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
        const val DEFAULT_API_VERSION = "2023-06-01"

        fun getInstance(): AICodingAgentSettingsState = service()
    }
}
