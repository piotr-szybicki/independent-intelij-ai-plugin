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
enum class AuthScheme(val headerName: String, val displayName: String, val aliases: List<String>) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)", listOf("anthropic")),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)", listOf("bearer", "oauth")),
    API_KEY("api-key", "api-key (Microsoft Foundry)", listOf("foundry"));

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token

    companion object {

        /**
         * The scheme a `header-type` in [AgentConfiguration] names, or null when it names none.
         *
         * Matched against the header itself as well as the enum's own name, because the header is
         * what the file is really saying and what a provider's documentation calls it -- the enum
         * name is only there so a file written from the settings XML's vocabulary still reads.
         */
        fun parse(value: String): AuthScheme? {
            // "Authorization: Bearer" is how the header is usually written down, and the half before
            // the colon is the part that names it.
            val wanted = value.trim().lowercase().substringBefore(':').trim()
            return entries.firstOrNull { scheme ->
                wanted == scheme.headerName.lowercase() ||
                    wanted == scheme.name.lowercase() ||
                    wanted in scheme.aliases
            }
        }
    }
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
    ;

    /** What this level is called in the [AgentConfiguration] file. */
    val fileName: String get() = wireValue ?: PROVIDER_DEFAULT_NAME

    companion object {

        /** The level an `effort` field names, or null when it names none. */
        fun parse(value: String): Effort? {
            val wanted = value.trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { effort ->
                wanted == effort.fileName || wanted == effort.name.lowercase().replace('_', '-')
            } ?: PROVIDER_DEFAULT.takeIf { wanted in PROVIDER_DEFAULT_ALIASES }
        }
    }
}

/** What the file calls "send nothing and let the endpoint decide", shared by both settings below. */
internal const val PROVIDER_DEFAULT_NAME = "provider-default"
internal val PROVIDER_DEFAULT_ALIASES = listOf(PROVIDER_DEFAULT_NAME, "default", "provider", "unset")

/**
 * Whether the model thinks before answering.
 *
 * Worth stating rather than leaving out: on the current models an absent `thinking` field means
 * adaptive thinking is *on*, which reverses what the same request did a generation ago. Thinking is
 * billed at the output rate and shares [AgentConfiguration.maxTokens] with the answer itself, so
 * leaving it unsaid is neither free nor obviously the default it looks like.
 *
 * [OFF] is the cheaper setting for a chat of small mechanical edits, at the price of a model that
 * reaches for its tools less readily. [PROVIDER_DEFAULT] sends nothing, for endpoints that reject
 * the field.
 */
enum class ThinkingMode(val displayName: String, val fileName: String, private val aliases: List<String>) {
    PROVIDER_DEFAULT("Provider default (field not sent)", PROVIDER_DEFAULT_NAME, emptyList()),
    ADAPTIVE("On -- the model decides how much (recommended)", "on", listOf("adaptive", "true", "yes", "enabled")),
    OFF("Off", "off", listOf("false", "no", "disabled", "none")),
    ;

    companion object {

        /**
         * The mode a `thinking` field names, or null when it names none. `true` and `false` are
         * among the aliases so that writing the field as a JSON boolean reads the way it looks.
         */
        fun parse(value: String): ThinkingMode? {
            val wanted = value.trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { mode ->
                wanted == mode.fileName || wanted == mode.name.lowercase() || wanted in mode.aliases
            } ?: PROVIDER_DEFAULT.takeIf { wanted in PROVIDER_DEFAULT_ALIASES }
        }
    }
}

@Service(Service.Level.APP)
@State(name = "AICodingAgentChatSettings", storages = [Storage("aiCodingAgentChatSettings.xml")])
class AICodingAgentSettingsState : PersistentStateComponent<AICodingAgentSettingsState.State> {

    data class State(
        /**
         * Which entry of the project's [AgentConfiguration] file requests go out with, by name.
         *
         * The name rather than the configuration itself: model, URL, token and token header live in
         * the file, and copying them here would be two answers to the same question. A name that
         * the file does not have falls back to its first entry -- see [AgentConfigurations.select]
         * -- which is what makes this survive a file being edited, renamed or swapped for another
         * project's.
         */
        var activeConfiguration: String = "",

        /**
         * How many request/tool-call rounds one message gets before the agent stops and asks whether
         * to carry on. A stop, not a ceiling -- saying yes buys another run of that many -- so this
         * is really "how long before I get asked", and it is only here to catch a loop that has run
         * away with itself.
         *
         * Here rather than in the configuration file, unlike everything else about a request: it is
         * a guard on the agent loop rather than a property of the model, and the answer to "how long
         * before I get asked" does not change when the provider does.
         */
        var maxIterations: Int = 10,

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
        fun getInstance(): AICodingAgentSettingsState = service()
    }
}
