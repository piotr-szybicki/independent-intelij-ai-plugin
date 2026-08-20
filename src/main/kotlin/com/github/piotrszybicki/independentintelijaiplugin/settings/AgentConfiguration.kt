package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AgentConfigurationException(message: String) : Exception(message)

data class AgentConfiguration(
    val name: String,

    val model: String,

    val models: List<String>,

    val url: String,
    val token: String,
    val authScheme: AuthScheme,
    val protocol: WireProtocol,

    val thinking: ThinkingMode,

    val effort: Effort,

    val maxTokens: Int,

    val contextWindowTokens: Int,

    val requestTimeoutSeconds: Int,
    val apiVersion: String,
    val extraHeaders: Map<String, String>,
) {

    val tokenEnvVar: String? get() = envVarName(token)

    fun withModel(wanted: String): AgentConfiguration =
        if (wanted.isNotBlank() && wanted in models) copy(model = wanted) else this

    val resolvedToken: String
        get() = tokenEnvVar?.let { System.getenv(it)?.trim().orEmpty() } ?: token.trim()

    val tokenDescription: String
        get() = tokenEnvVar?.let { variable ->
            if (resolvedToken.isBlank()) "\$$variable -- empty or undefined in the IDE's environment"
            else "\$$variable -- set"
        } ?: if (token.isBlank()) "not set in the configuration file" else "written in the configuration file"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(NAME, name)
        addProperty(MODEL, model)
        add(MODELS, JsonArray().apply { models.forEach { add(it) } })
        addProperty(URL, url)
        addProperty(TOKEN, token)
        addProperty(HEADER_TYPE, authScheme.headerName)
        addProperty(PROTOCOL, protocol.wireName)
        addProperty(THINKING, thinking.fileName)
        addProperty(EFFORT, effort.fileName)
        addProperty(MAX_TOKENS, maxTokens)
        addProperty(CONTEXT_WINDOW, contextWindowTokens)
        addProperty(REQUEST_TIMEOUT, requestTimeoutSeconds)
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

        const val FILE_NAME = "independent-ai-plugin-settings.json"

        const val PATH_ENV_VAR = "INTELIJ_AI_SETTINGS"

        fun configuredPath(raw: String?): Path? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val home = System.getProperty("user.home").orEmpty()
            val expanded = when {
                trimmed == "~" -> home
                trimmed.startsWith("~/") || trimmed.startsWith("~\\") -> home + trimmed.substring(1)
                else -> trimmed
            }
            val path = runCatching { Paths.get(expanded).toAbsolutePath().normalize() }.getOrNull()
                ?: return null
            return if (Files.isDirectory(path)) path.resolve(FILE_NAME) else path
        }

        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val DEFAULT_ENDPOINT_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_API_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8000
        const val DEFAULT_CONTEXT_WINDOW = 200_000

        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 60

        private const val CONFIGURATIONS = "configurations"
        private const val NAME = "name"
        private const val MODEL = "model"
        private const val MODELS = "models"
        private const val URL = "url"
        private const val TOKEN = "token"
        private const val HEADER_TYPE = "header-type"
        private const val PROTOCOL = "protocol"
        private const val THINKING = "thinking"
        private const val EFFORT = "effort"
        private const val MAX_TOKENS = "max-tokens"
        private const val CONTEXT_WINDOW = "context-window"
        private const val REQUEST_TIMEOUT = "request-timeout-seconds"
        private const val CUSTOMIZATIONS = "additional-customizations"
        private const val API_VERSION = "anthropic-version"
        private const val EXTRA_HEADERS = "extra-headers"

        val DEFAULT = AgentConfiguration(
            name = "Anthropic Claude",
            model = DEFAULT_MODEL,
            models = listOf(DEFAULT_MODEL, "claude-opus-5", "claude-haiku-4-5-20251001"),
            url = DEFAULT_ENDPOINT_URL,
            token = "$" + AICodingAgentCredentials.ENV_VAR,
            authScheme = AuthScheme.X_API_KEY,
            protocol = WireProtocol.ANTHROPIC_MESSAGES,
            thinking = ThinkingMode.ADAPTIVE,
            effort = Effort.MEDIUM,
            maxTokens = DEFAULT_MAX_TOKENS,
            contextWindowTokens = DEFAULT_CONTEXT_WINDOW,
            requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS,
            apiVersion = DEFAULT_API_VERSION,
            extraHeaders = emptyMap(),
        )

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

        val STARTER = listOf(
            DEFAULT,
            AgentConfiguration(
                name = "OpenAI GPT",
                model = "gpt-5",
                models = listOf("gpt-5", "gpt-5-mini"),
                url = "https://api.openai.com/v1/responses",
                token = "\$OPENAI_API_KEY",
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_RESPONSES,
                thinking = ThinkingMode.ADAPTIVE,
                effort = Effort.MEDIUM,
                maxTokens = DEFAULT_MAX_TOKENS,
                contextWindowTokens = DEFAULT_CONTEXT_WINDOW,
                requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS,
                apiVersion = "",
                extraHeaders = emptyMap(),
            ),
            AgentConfiguration(
                name = "Local Ollama",
                model = "qwen3-coder",
                models = listOf("qwen3-coder"),
                url = "http://localhost:11434/v1/chat/completions",
                token = "ollama",
                authScheme = AuthScheme.BEARER,
                protocol = WireProtocol.OPENAI_CHAT_COMPLETIONS,
                thinking = ThinkingMode.PROVIDER_DEFAULT,
                effort = Effort.PROVIDER_DEFAULT,
                maxTokens = DEFAULT_MAX_TOKENS,
                contextWindowTokens = 32_000,
                requestTimeoutSeconds = 300,
                apiVersion = "",
                extraHeaders = emptyMap(),
            ),
        )

        fun envVarName(token: String): String? {
            val trimmed = token.trim()
            if (!trimmed.startsWith("$")) return null
            val body = trimmed.drop(1).let { if (it.startsWith("{") && it.endsWith("}")) it.drop(1).dropLast(1) else it }
            return body.trim().takeIf { it.isNotBlank() }
        }

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

            val listed = entry.strings(name, MODELS)
            val model = entry.string(MODEL)?.trim().orEmpty().ifBlank { listed.firstOrNull().orEmpty() }
            if (model.isBlank()) {
                throw AgentConfigurationException("\"$name\" has no \"$MODEL\" and no \"$MODELS\"")
            }
            val models = if (model in listed) listed else listOf(model) + listed

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
                models = models,
                url = url,
                token = entry.string(TOKEN).orEmpty().trim(),
                authScheme = authScheme,
                protocol = protocol,
                thinking = thinking,
                effort = effort,
                maxTokens = entry.int(name, MAX_TOKENS, DEFAULT_MAX_TOKENS, minimum = 1),
                contextWindowTokens = entry.int(name, CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW, minimum = 0),
                requestTimeoutSeconds = entry.int(name, REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT_SECONDS, minimum = 1),
                apiVersion = customizations?.string(API_VERSION)?.trim()
                    ?: if (protocol == WireProtocol.ANTHROPIC_MESSAGES) DEFAULT_API_VERSION else "",
                extraHeaders = customizations?.stringMap(name, EXTRA_HEADERS).orEmpty(),
            )
        }

        fun render(
            configurations: List<AgentConfiguration>,
            database: UsageDatabaseConfig = UsageDatabaseConfig.OFF,
            findInFiles: FindInFilesConfig = FindInFilesConfig.DEFAULT,
            agents: AgentRosterConfig = AgentRosterConfig.EMPTY,
            summarizer: SummarizerConfig = SummarizerConfig.DEFAULT,
            conversationDefaults: ConversationDefaultsConfig = ConversationDefaultsConfig.DEFAULT,
        ): String {
            val root = JsonObject().apply {
                add(UsageDatabaseConfig.SECTION, database.toJson())
                add(FindInFilesConfig.SECTION, findInFiles.toJson())
                add(SummarizerConfig.SECTION, summarizer.toJson())
                add(ConversationDefaultsConfig.SECTION, conversationDefaults.toJson())
                if (agents.agents.isNotEmpty()) add(AgentRosterConfig.SECTION, agents.toJson())
                add(CONFIGURATIONS, JsonArray().apply { configurations.forEach { add(it.toJson()) } })
            }
            return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
        }

        private fun JsonObject.string(field: String): String? =
            get(field)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.int(configuration: String, field: String, fallback: Int, minimum: Int): Int {
            val element = get(field) ?: return fallback
            val value = element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull()
                ?: throw AgentConfigurationException("\"$configuration\".$field must be a whole number")
            if (value < minimum) {
                throw AgentConfigurationException("\"$configuration\".$field must be at least $minimum")
            }
            return value
        }

        private fun JsonObject.strings(configuration: String, field: String): List<String> {
            val element = get(field) ?: return emptyList()
            if (!element.isJsonArray) {
                throw AgentConfigurationException("\"$configuration\".$field must be an array of names")
            }
            return element.asJsonArray
                .filter { it.isJsonPrimitive }
                .map { it.asString.trim() }
                .filter { it.isNotBlank() }
                .distinct()
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
