package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.github.piotrszybicki.independentintelijaiplugin.skills.SkillRoot
import com.github.piotrszybicki.independentintelijaiplugin.tools.ToolCatalog

enum class AuthScheme(val headerName: String, val displayName: String, val aliases: List<String>) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)", listOf("anthropic")),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)", listOf("bearer", "oauth")),
    API_KEY("api-key", "api-key (Microsoft Foundry)", listOf("foundry"));

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token

    companion object {

        fun parse(value: String): AuthScheme? {
            val wanted = value.trim().lowercase().substringBefore(':').trim()
            return entries.firstOrNull { scheme ->
                wanted == scheme.headerName.lowercase() ||
                    wanted == scheme.name.lowercase() ||
                    wanted in scheme.aliases
            }
        }
    }
}

enum class Effort(val wireValue: String?, val openAiValue: String?, val displayName: String) {
    PROVIDER_DEFAULT(null, null, "Provider default (field not sent)"),
    LOW("low", "low", "Low -- short, scoped work"),
    MEDIUM("medium", "medium", "Medium (recommended)"),
    HIGH("high", "high", "High -- the provider's own default"),
    XHIGH("xhigh", "high", "Extra high -- hard agentic work"),
    MAX("max", "high", "Maximum -- correctness over cost"),
    ;

    val fileName: String get() = wireValue ?: PROVIDER_DEFAULT_NAME

    companion object {

        fun parse(value: String): Effort? {
            val wanted = value.trim().lowercase().replace('_', '-').replace(' ', '-')
            return entries.firstOrNull { effort ->
                wanted == effort.fileName || wanted == effort.name.lowercase().replace('_', '-')
            } ?: PROVIDER_DEFAULT.takeIf { wanted in PROVIDER_DEFAULT_ALIASES }
        }
    }
}

internal const val PROVIDER_DEFAULT_NAME = "provider-default"
internal val PROVIDER_DEFAULT_ALIASES = listOf(PROVIDER_DEFAULT_NAME, "default", "provider", "unset")

enum class ThinkingMode(val displayName: String, val fileName: String, private val aliases: List<String>) {
    PROVIDER_DEFAULT("Provider default (field not sent)", PROVIDER_DEFAULT_NAME, emptyList()),
    ADAPTIVE("On -- the model decides how much (recommended)", "on", listOf("adaptive", "true", "yes", "enabled")),
    OFF("Off", "off", listOf("false", "no", "disabled", "none")),
    ;

    companion object {

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
        var activeConfiguration: String = "",

        var activeModel: String = "",

        var maxIterations: Int = 10,

        var maxToolOutputTokens: Int = 500,

        var mcpServers: String = "",

        var confirmMcpToolCalls: Boolean = true,

        var enabledTools: String = ToolCatalog.DEFAULT_ENABLED,

        var enabledMcpTools: String = "",

        var skillPaths: String = SkillRoot.DEFAULT_PATHS,

        var enabledSkills: String = "",
    )

    /*
     * Where the usage database went: into the `usage-database` section of
     * [AgentConfiguration.FILE_NAME], alongside the providers -- see [UsageDatabaseConfig]. It names
     * a server that belongs to the project rather than to the IDE, and a URL kept here was one more
     * thing that did not travel with the project it recorded.
     *
     * An older IDE's XML still holds `usageDatabaseUrl` and `logUsageToDatabase`; both are ignored
     * from here on and the URL has to be moved into the file by hand. Reading them as a fallback
     * would mean two places that answer the same question, which is the arrangement the file exists
     * to end.
     */

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): AICodingAgentSettingsState = service()
    }
}
