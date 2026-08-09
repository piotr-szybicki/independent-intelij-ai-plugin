package com.github.piotrszybicki.independentintelijaiplugin.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Raised when the configuration text cannot be turned into a list of servers. */
class McpConfigException(message: String) : Exception(message)

/**
 * One MCP server the plugin connects to.
 *
 * Servers come in two shapes and an entry picks between them by which field it carries: a [command]
 * is a local process spoken to over its own stdin/stdout, a [url] is a remote endpoint spoken to
 * over HTTP. Nothing else about the two differs -- both sides exchange the same JSON-RPC messages --
 * so everything above [McpTransport] is written once and works for either.
 */
data class McpServerConfig(
    val name: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    val isStdio: Boolean get() = !command.isNullOrBlank()

    /** What to show the user when naming this server in a dialog or an error. */
    val target: String get() = if (isStdio) (listOf(command) + args).joinToString(" ") else url.orEmpty()

    /**
     * A copy with every `${env:NAME}` reference replaced by the variable's value.
     *
     * Resolved here rather than at parse time so that a server whose token is missing reports that
     * as its own connection failure, instead of a bad variable in one entry making the whole
     * settings field unparseable and taking every other server down with it.
     */
    fun resolved(): McpServerConfig = copy(
        command = command?.let(::expandEnv),
        args = args.map(::expandEnv),
        env = env.mapValues { expandEnv(it.value) },
        url = url?.let(::expandEnv),
        headers = headers.mapValues { expandEnv(it.value) },
    )

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60

        /**
         * Placeholder for a value that must not be written to the settings XML -- an API token, most
         * of the time. The same reasoning as [com.github.piotrszybicki.independentintelijaiplugin.settings.AnthropicCredentials]:
         * secrets live in the environment, and the IDE only sees what was set before it launched.
         */
        private val ENV_REFERENCE = Regex("""\$\{env:([A-Za-z_][A-Za-z0-9_]*)}""")

        private fun expandEnv(value: String): String = ENV_REFERENCE.replace(value) { match ->
            val variable = match.groupValues[1]
            System.getenv(variable)
                ?: throw McpConfigException("the environment variable $variable is not set in the IDE's environment")
        }

        /**
         * Reads the `mcpServers` object every other MCP client uses, so a server's own README can be
         * pasted in unchanged. The wrapper is optional -- a bare object of name-to-entry is read the
         * same way, since that is what is left after copying one entry out of a larger file.
         */
        fun parseAll(text: String): List<McpServerConfig> {
            if (text.isBlank()) return emptyList()

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw McpConfigException("not valid JSON: ${e.message}")
            }
            if (root == null || !root.isJsonObject) {
                throw McpConfigException("expected a JSON object at the top level")
            }

            val obj = root.asJsonObject
            val wrapped = obj.get("mcpServers")
            val entries = when {
                wrapped == null -> obj
                wrapped.isJsonObject -> wrapped.asJsonObject
                else -> throw McpConfigException("\"mcpServers\" must be an object of server name to configuration")
            }

            return entries.entrySet().map { (name, element) ->
                if (!element.isJsonObject) {
                    throw McpConfigException("\"$name\" must be an object")
                }
                parseOne(name, element.asJsonObject)
            }
        }

        private fun parseOne(name: String, entry: JsonObject): McpServerConfig {
            val command = entry.get("command")?.asString?.takeIf { it.isNotBlank() }
            val url = entry.get("url")?.asString?.takeIf { it.isNotBlank() }
            if (command == null && url == null) {
                throw McpConfigException("\"$name\" needs either a \"command\" (local process) or a \"url\" (remote server)")
            }
            if (command != null && url != null) {
                throw McpConfigException("\"$name\" has both \"command\" and \"url\"; a server is one or the other")
            }

            // `disabled` is what the other clients' config files use; `enabled` reads better in a
            // hand-written entry. Accept both rather than making the user guess which one works.
            val enabled = entry.get("enabled")?.asBoolean ?: entry.get("disabled")?.asBoolean?.not() ?: true

            return McpServerConfig(
                name = name,
                command = command,
                args = entry.getAsJsonArray("args")?.map { it.asString }.orEmpty(),
                env = stringMap(name, entry, "env"),
                url = url,
                headers = stringMap(name, entry, "headers"),
                enabled = enabled,
                timeoutSeconds = (entry.get("timeoutSeconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, 600),
            )
        }

        private fun stringMap(server: String, entry: JsonObject, field: String): Map<String, String> {
            val element = entry.get(field) ?: return emptyMap()
            if (!element.isJsonObject) throw McpConfigException("\"$server\".$field must be an object of name to value")
            return element.asJsonObject.entrySet().associate { (key, value) -> key to value.asString }
        }
    }
}
