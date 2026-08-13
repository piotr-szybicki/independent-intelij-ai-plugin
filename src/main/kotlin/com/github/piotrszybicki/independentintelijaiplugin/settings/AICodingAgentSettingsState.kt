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

/**
 * How much the model may spend on thinking and tool calls before it answers.
 *
 * Sent as `output_config.effort`, and worth setting rather than leaving to the provider: the current
 * models default to `high`, thinking is billed at the output rate, and a chat that is mostly small
 * edits pays that on every request of every tool-call round. Medium is the balance point on the
 * current models -- roughly where the previous generation sat at high.
 *
 * [PROVIDER_DEFAULT] sends nothing, which is the escape hatch for the older models and the gateways
 * that reject the field outright rather than ignoring it.
 */
enum class Effort(val wireValue: String?, val openAiValue: String?, val displayName: String) {
    PROVIDER_DEFAULT(null, null, "Provider default (field not sent)"),
    LOW("low", "low", "Low -- short, scoped work"),
    MEDIUM("medium", "medium", "Medium (recommended)"),
    HIGH("high", "high", "High -- the provider's own default"),
    // OpenAI's scale stops at high, and a level it does not know is a rejected request rather than a
    // clamped one -- so the two levels above it are clamped here instead.
    XHIGH("xhigh", "high", "Extra high -- hard agentic work"),
    MAX("max", "high", "Maximum -- correctness over cost"),
}

/**
 * Whether the model thinks before answering.
 *
 * Worth stating rather than leaving out: on the current models an absent `thinking` field means
 * adaptive thinking is *on*, which reverses what the same request did a generation ago. Thinking is
 * billed at the output rate and shares [AICodingAgentSettingsState.State.maxTokens] with the answer
 * itself, so leaving it unsaid is neither free nor obviously the default it looks like.
 *
 * [OFF] is the cheaper setting for a chat of small mechanical edits, at the price of a model that
 * reaches for its tools less readily. [PROVIDER_DEFAULT] sends nothing, for endpoints that reject
 * the field.
 */
enum class ThinkingMode(val displayName: String) {
    PROVIDER_DEFAULT("Provider default (field not sent)"),
    ADAPTIVE("On -- the model decides how much (recommended)"),
    OFF("Off"),
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
         *
         * Set high enough that hitting it is unusual, because a cap is not a budget: only the tokens
         * actually written are billed, while every reply cut off by this spends a second request
         * that re-sends the whole conversation to say the rest. Room the model does not use is free;
         * room it needed and did not have is charged for twice. It also has to cover the thinking,
         * which shares the cap with the answer -- see [ThinkingMode].
         */
        var maxTokens: Int = 8000,

        /** How hard the model is asked to work per request. See [Effort]. */
        var effort: Effort = Effort.MEDIUM,

        /** Whether the model thinks before answering. See [ThinkingMode]. */
        var thinkingMode: ThinkingMode = ThinkingMode.ADAPTIVE,

        /**
         * How many request/tool-call rounds one message gets before the agent stops and asks whether
         * to carry on. A stop, not a ceiling -- saying yes buys another run of that many -- so this
         * is really "how long before I get asked", and it is only here to catch a loop that has run
         * away with itself.
         */
        var maxIterations: Int = 10,

        /**
         * The model's context window, which is what
         * [com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.HistoryCompaction]
         * measures a conversation against before deciding to drop old tool output.
         *
         * A setting rather than something read off the model name: the endpoint can be any provider
         * and any gateway, the name is free text, and guessing 200k for a model that has 128k is
         * a conversation that grows until the provider refuses it. Zero switches compaction off.
         */
        var contextWindowTokens: Int = 200_000,

        /**
         * The full URL of the messages endpoint, path included, rather than a base URL with the path
         * appended: provider paths do not agree (Foundry's base already ends in `/v1`, gateways add
         * prefixes of their own), so guessing at it would break more setups than it saves typing on.
         *
         * The fallback rather than the answer: [EndpointUrl] takes the environment first, and this
         * only stands when nothing outside the settings file has anything to say.
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
