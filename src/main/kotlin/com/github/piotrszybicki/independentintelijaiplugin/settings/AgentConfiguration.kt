package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Raised when the configuration file cannot be turned into a list of configurations. */
class AgentConfigurationException(message: String) : Exception(message)

/**
 * One provider the plugin can be pointed at -- everything that has to change together when you
 * switch model or provider, named so it can be picked from a dropdown.
 *
 * Lives in [FILE_NAME] in the project root rather than in the settings XML, because these five
 * things are never independently useful: a model name belongs to a provider, which decides the URL,
 * which decides the token header and which token the request needs. Editing them one field at a time
 * meant a settings page that was briefly wrong at every step. A file also puts the set of providers
 * somewhere it can be kept with the project and copied between machines.
 *
 * The token is the one field that is allowed to name something instead of holding it: a value
 * starting with `$` is the name of an environment variable, which is where a secret should be when
 * the file is in the project root. Anything else is used literally, which is what a local server
 * wanting a placeholder needs.
 */
data class AgentConfiguration(
    /** What this configuration is called in the settings dropdown. Unique within the file. */
    val name: String,
    val model: String,
    val url: String,
    /** As written in the file: `$NAME` to read an environment variable, or the token itself. */
    val token: String,
    val authScheme: AuthScheme,
    val protocol: WireProtocol,

    /**
     * Whether the model thinks before answering. Per configuration rather than per IDE because it
     * is a property of the model at the other end: the Messages API and OpenAI Responses carry it
     * in fields of their own, and Chat Completions carries it in neither -- see [ThinkingMode].
     */
    val thinking: ThinkingMode,

    /** How hard the model is asked to work per request. See [Effort]. */
    val effort: Effort,

    /**
     * The starting cap on a single reply. A starting point rather than a fixed one: saying yes to
     * continuing a cut-off reply doubles it for the rest of that chat, so a conversation that needs
     * longer answers finds its own level without this being edited.
     *
     * Set high enough that hitting it is unusual, because a cap is not a budget: only the tokens
     * actually written are billed, while every reply cut off by this spends a second request that
     * re-sends the whole conversation to say the rest. Room the model does not use is free; room it
     * needed and did not have is charged for twice. It also has to cover the thinking, which shares
     * the cap with the answer.
     */
    val maxTokens: Int,

    /**
     * The model's context window, which is what
     * [com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.HistoryCompaction] measures
     * a conversation against before deciding to drop old tool output.
     *
     * Stated rather than read off the model name: the name is free text and the endpoint can be any
     * gateway, so guessing 200k for a model that has 128k is a conversation that grows until the
     * provider refuses it. Zero switches compaction off.
     */
    val contextWindowTokens: Int,

    /** Ignored unless [protocol] is the Messages API: no other provider knows the header. */
    val apiVersion: String,
    /** Routing or tenancy headers a gateway needs. Never secrets -- the file is plain text. */
    val extraHeaders: Map<String, String>,
) {

    /** The variable [token] names, or null when it carries the token itself. */
    val tokenEnvVar: String? get() = envVarName(token)

    /**
     * The token to send: the named variable's value, or the literal. Blank when the variable is
     * unset, which [com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentEndpoint.validate]
     * turns into a message naming the variable rather than a 401 from the provider.
     */
    val resolvedToken: String
        get() = tokenEnvVar?.let { System.getenv(it)?.trim().orEmpty() } ?: token.trim()

    /** Where the token comes from, for the settings page -- never the token itself. */
    val tokenDescription: String
        get() = tokenEnvVar?.let { variable ->
            if (resolvedToken.isBlank()) "\$$variable -- empty or undefined in the IDE's environment"
            else "\$$variable -- set"
        } ?: if (token.isBlank()) "not set in the configuration file" else "written in the configuration file"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(NAME, name)
        addProperty(MODEL, model)
        addProperty(URL, url)
        addProperty(TOKEN, token)
        addProperty(HEADER_TYPE, authScheme.headerName)
        addProperty(PROTOCOL, protocol.wireName)
        addProperty(THINKING, thinking.fileName)
        addProperty(EFFORT, effort.fileName)
        addProperty(MAX_TOKENS, maxTokens)
        addProperty(CONTEXT_WINDOW, contextWindowTokens)
        add(
            CUSTOMIZATIONS,
            JsonObject().apply {
                addProperty(API_VERSION, apiVersion)
                add(
                    EXTRA_HEADERS,
                    JsonObject().apply { extraHeaders.forEach { (key, value) -> addProperty(key, value) } },
                )
            },
        )
    }

    companion object {

        /** Sits in the project root, next to the code it configures. */
        const val FILE_NAME = "independent-ai-plugin-settings.json"

        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val DEFAULT_ENDPOINT_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_API_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8000
        const val DEFAULT_CONTEXT_WINDOW = 200_000

        private const val CONFIGURATIONS = "configurations"
        private const val NAME = "name"
        private const val MODEL = "model"
        private const val URL = "url"
        private const val TOKEN = "token"
        private const val HEADER_TYPE = "header-type"
        private const val PROTOCOL = "protocol"
        private const val THINKING = "thinking"
        private const val EFFORT = "effort"
        private const val MAX_TOKENS = "max-tokens"
        private const val CONTEXT_WINDOW = "context-window"
        private const val CUSTOMIZATIONS = "additional-customizations"
        private const val API_VERSION = "anthropic-version"
        private const val EXTRA_HEADERS = "extra-headers"

        /** What a missing, empty or unreadable file falls back to, so the chat still has an endpoint. */
        val DEFAULT = AgentConfiguration(
            name = "Anthropic Claude",
            model = DEFAULT_MODEL,
            url = DEFAULT_ENDPOINT_URL,
            token = "$" + AICodingAgentCredentials.ENV_VAR,
            authScheme = AuthScheme.X_API_KEY,
            protocol = WireProtocol.ANTHROPIC_MESSAGES,
            thinking = ThinkingMode.ADAPTIVE,
            effort = Effort.MEDIUM,
            maxTokens = DEFAULT_MAX_TOKENS,
            contextWindowTokens = DEFAULT_CONTEXT_WINDOW,
            apiVersion = DEFAULT_API_VERSION,
            extraHeaders = emptyMap(),
        )

        /**
         * What to run on when the file has nothing to offer -- missing, empty or unreadable.
         *
         * The one place [EndpointUrl.ENV_VAR] still has a say, because it is the one place there is
         * no entry for it to contradict. Everything the URL decides is re-read from it rather than
         * kept from [DEFAULT]: a variable pointing at a Responses endpoint would otherwise be sent
         * a Messages request, which is the mismatch this fallback exists to avoid.
         */
        fun fallback(): AgentConfiguration {
            val url = EndpointUrl.resolve(DEFAULT_ENDPOINT_URL)
            if (url == DEFAULT_ENDPOINT_URL) return DEFAULT

            val detected = ProviderProfile.detect(url)
            val protocol = detected?.protocol ?: DEFAULT.protocol
            return DEFAULT.copy(
                url = url,
                protocol = protocol,
                authScheme = detected?.authScheme ?: DEFAULT.authScheme,
                thinking = ProviderProfile.defaultThinking(protocol),
                apiVersion = if (protocol == WireProtocol.ANTHROPIC_MESSAGES) DEFAULT_API_VERSION else "",
            )
        }

        /**
         * What gets written the first time a project is opened: one entry per wire protocol, so
         * switching provider is editing a line rather than working out the shape of an entry.
         */
        val STARTER = listOf(
            DEFAULT,
            AgentConfiguration(
                name = "OpenAI GPT",
                model = "gpt-5",
                url = "https://api.openai.com/v1/responses",
                token = "\$OPENAI_API_KEY",
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_RESPONSES,
                thinking = ThinkingMode.ADAPTIVE,
                effort = Effort.MEDIUM,
                maxTokens = DEFAULT_MAX_TOKENS,
                contextWindowTokens = DEFAULT_CONTEXT_WINDOW,
                apiVersion = "",
                extraHeaders = emptyMap(),
            ),
            AgentConfiguration(
                name = "Local Ollama",
                model = "qwen3-coder",
                url = "http://localhost:11434/v1/chat/completions",
                // Literal rather than a variable: a local server wants a token it will not look at,
                // and this is the example of the plain-text form.
                token = "ollama",
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_CHAT_COMPLETIONS,
                // Nothing is sent on Chat Completions whatever this says, because too many of the
                // compatible servers answer a field they do not know with a 400.
                thinking = ThinkingMode.PROVIDER_DEFAULT,
                effort = Effort.PROVIDER_DEFAULT,
                maxTokens = DEFAULT_MAX_TOKENS,
                // A local model is usually served with far less window than it was trained for.
                contextWindowTokens = 32_000,
                apiVersion = "",
                extraHeaders = emptyMap(),
            ),
        )

        /**
         * The variable a token field names, or null when it holds the token itself.
         *
         * `$NAME` and `${NAME}` both work; the braced form is what you need when the name is
         * followed by anything else in the same string.
         */
        fun envVarName(token: String): String? {
            val trimmed = token.trim()
            if (!trimmed.startsWith("$")) return null
            val body = trimmed.drop(1).let { if (it.startsWith("{") && it.endsWith("}")) it.drop(1).dropLast(1) else it }
            return body.trim().takeIf { it.isNotBlank() }
        }

        /**
         * Reads the file's `configurations` array. A bare array at the top level is read the same
         * way, since that is what is left after copying the array out of a larger file.
         *
         * Throws rather than dropping a bad entry: unlike an MCP server, where one broken entry
         * costs a few tools, a configuration that silently parsed as something else is a chat
         * quietly running against the wrong provider.
         */
        fun parseAll(text: String): List<AgentConfiguration> {
            if (text.isBlank()) return emptyList()

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }

            val array: JsonArray = when {
                root == null || root.isJsonNull -> throw AgentConfigurationException("empty")
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.get(CONFIGURATIONS)?.let {
                    if (it.isJsonArray) it.asJsonArray
                    else throw AgentConfigurationException("\"$CONFIGURATIONS\" must be an array")
                } ?: throw AgentConfigurationException("expected a \"$CONFIGURATIONS\" array at the top level")
                else -> throw AgentConfigurationException("expected a JSON object at the top level")
            }

            val seen = mutableSetOf<String>()
            return array.mapIndexed { index, element ->
                if (!element.isJsonObject) {
                    throw AgentConfigurationException("configuration ${index + 1} must be an object")
                }
                parseOne(index, element.asJsonObject).also {
                    if (!seen.add(it.name)) {
                        throw AgentConfigurationException("two configurations are both called \"${it.name}\"")
                    }
                }
            }
        }

        private fun parseOne(index: Int, entry: JsonObject): AgentConfiguration {
            val where = "configuration ${index + 1}"
            val name = entry.string(NAME).orEmpty().trim()
            if (name.isBlank()) throw AgentConfigurationException("$where has no \"$NAME\"")

            val url = entry.string(URL).orEmpty().trim()
            if (url.isBlank()) throw AgentConfigurationException("\"$name\" has no \"$URL\"")

            val model = entry.string(MODEL).orEmpty().trim()
            if (model.isBlank()) throw AgentConfigurationException("\"$name\" has no \"$MODEL\"")

            // Both of these are optional, because the URL already says what they are for every
            // provider whose URL says anything -- see [ProviderProfile]. Stating them is for the
            // gateway serving one API from a path that looks like another's.
            val detected = ProviderProfile.detect(url)

            val protocolName = entry.string(PROTOCOL)?.trim()
            val protocol = when {
                protocolName.isNullOrBlank() -> detected?.protocol ?: WireProtocol.ANTHROPIC_MESSAGES
                else -> WireProtocol.parse(protocolName)
                    ?: throw AgentConfigurationException(
                        "\"$name\" has an unknown \"$PROTOCOL\" of \"$protocolName\" -- expected one of " +
                            WireProtocol.entries.joinToString(", ") { it.wireName },
                    )
            }

            val headerType = entry.string(HEADER_TYPE)?.trim()
            val authScheme = when {
                // The host settles it where it is one of the known ones; otherwise the protocol is
                // the better guess than a fixed default, since everything OpenAI-shaped takes a
                // bearer token and the Messages API takes x-api-key.
                headerType.isNullOrBlank() -> detected?.authScheme ?: when (protocol) {
                    WireProtocol.ANTHROPIC_MESSAGES -> AuthScheme.X_API_KEY
                    else -> AuthScheme.BEARER
                }
                else -> AuthScheme.parse(headerType)
                    ?: throw AgentConfigurationException(
                        "\"$name\" has an unknown \"$HEADER_TYPE\" of \"$headerType\" -- expected one of " +
                            AuthScheme.entries.joinToString(", ") { it.headerName },
                    )
            }

            val thinkingName = entry.string(THINKING)?.trim()
            val thinking = when {
                // Absent means what the protocol can honestly do: on where there is a field to
                // carry it, and nothing at all on Chat Completions -- see [ProviderProfile].
                thinkingName.isNullOrBlank() -> ProviderProfile.defaultThinking(protocol)
                else -> ThinkingMode.parse(thinkingName)
                    ?: throw AgentConfigurationException(
                        "\"$name\" has an unknown \"$THINKING\" of \"$thinkingName\" -- expected " +
                            ThinkingMode.entries.joinToString(", ") { it.fileName },
                    )
            }

            val effortName = entry.string(EFFORT)?.trim()
            val effort = when {
                effortName.isNullOrBlank() -> Effort.MEDIUM
                else -> Effort.parse(effortName)
                    ?: throw AgentConfigurationException(
                        "\"$name\" has an unknown \"$EFFORT\" of \"$effortName\" -- expected one of " +
                            Effort.entries.joinToString(", ") { it.fileName },
                    )
            }

            val customizations = entry.get(CUSTOMIZATIONS)?.takeIf { it.isJsonObject }?.asJsonObject

            return AgentConfiguration(
                name = name,
                model = model,
                url = url,
                token = entry.string(TOKEN).orEmpty().trim(),
                authScheme = authScheme,
                protocol = protocol,
                thinking = thinking,
                effort = effort,
                // A budget the agent counts down, so a zero or a negative is refused rather than
                // saved and acted on -- unlike the window below, where zero means "do not compact".
                maxTokens = entry.int(name, MAX_TOKENS, DEFAULT_MAX_TOKENS, minimum = 1),
                contextWindowTokens = entry.int(name, CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW, minimum = 0),
                // Absent means the Messages API default rather than "send nothing", so a file
                // written without the section keeps working against Anthropic. Present and empty
                // is the way to say the header should be left off.
                apiVersion = customizations?.string(API_VERSION)?.trim()
                    ?: if (protocol == WireProtocol.ANTHROPIC_MESSAGES) DEFAULT_API_VERSION else "",
                extraHeaders = customizations?.stringMap(name, EXTRA_HEADERS).orEmpty(),
            )
        }

        /** The file's whole contents, pretty-printed, as it is written on creation. */
        fun render(configurations: List<AgentConfiguration>): String {
            val root = JsonObject().apply {
                add(CONFIGURATIONS, JsonArray().apply { configurations.forEach { add(it.toJson()) } })
            }
            return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
        }

        private fun JsonObject.string(field: String): String? =
            get(field)?.takeIf { it.isJsonPrimitive }?.asString

        /**
         * A whole number from the file, [fallback] when the field is absent, and an error when it is
         * there but unusable. Absent is a real answer; "0" where a positive number is needed is a
         * mistake worth reporting rather than quietly replacing with something that works.
         */
        private fun JsonObject.int(configuration: String, field: String, fallback: Int, minimum: Int): Int {
            val element = get(field) ?: return fallback
            val value = element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull()
                ?: throw AgentConfigurationException("\"$configuration\".$field must be a whole number")
            if (value < minimum) {
                throw AgentConfigurationException("\"$configuration\".$field must be at least $minimum")
            }
            return value
        }

        private fun JsonObject.stringMap(configuration: String, field: String): Map<String, String> {
            val element = get(field) ?: return emptyMap()
            if (!element.isJsonObject) {
                throw AgentConfigurationException("\"$configuration\".$field must be an object of header name to value")
            }
            return element.asJsonObject.entrySet()
                .filter { it.value.isJsonPrimitive }
                .associate { (key, value) -> key to value.asString }
        }
    }
}
