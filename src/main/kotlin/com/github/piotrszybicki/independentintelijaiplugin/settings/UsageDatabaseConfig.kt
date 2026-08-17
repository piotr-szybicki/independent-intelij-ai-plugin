package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where one row per request is written, as the `usage-database` section of
 * [AgentConfiguration.FILE_NAME] says it -- see
 * [com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase].
 *
 * ```json
 * "usage-database": {
 *   "url": "jdbc:mysql://localhost:3306/ai_usage?user=root&password=${env:MYSQL_PASSWORD}",
 *   "enabled": true
 * }
 * ```
 *
 * In the file rather than in the settings XML, for the same reason the providers are: the server a
 * project's requests are recorded to belongs with the project and travels with it, and a URL is the
 * kind of thing that is easier to paste into a file than to retype into a field. It also puts the
 * one remaining thing a request is made of next to the rest of them.
 *
 * The URL is the driver's, in full, so everything MySQL takes as a connection option is available
 * without a field per option. `${env:NAME}` reads an environment variable, which is where the
 * password belongs -- this file is plain text and usually in version control. Expanding it is
 * [com.github.piotrszybicki.independentintelijaiplugin.logging.JdbcUrl]'s job, so what is held here
 * is always the unexpanded text and a secret never reaches this object.
 */
data class UsageDatabaseConfig(
    /** The JDBC URL as written, `${env:NAME}` and all. Empty means nothing is recorded. */
    val url: String,

    /**
     * Whether rows are actually written. True by default, which costs nothing while [url] is empty:
     * with nowhere to write to, nothing is attempted. It is how the writes are turned off without
     * losing the URL, which is what a server that is down for the afternoon needs.
     */
    val enabled: Boolean,
) {

    /** Whether anything will be written at all -- switched on, and pointed at something. */
    val isActive: Boolean get() = enabled && url.isNotBlank()

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(URL, url)
        addProperty(ENABLED, enabled)
    }

    companion object {

        /** The top-level field of [AgentConfiguration.FILE_NAME] this is read from. */
        const val SECTION = "usage-database"

        private const val URL = "url"
        private const val ENABLED = "enabled"

        /**
         * What a file with no section at all means, and what the starter file is written with: on,
         * but pointed at nothing. There is no sensible guess to make about where a server is.
         */
        val OFF = UsageDatabaseConfig(url = "", enabled = true)

        /**
         * The section of [text], or [OFF] when there is none.
         *
         * Absent is a real answer rather than a mistake -- the file predates this section and a
         * project that records nothing never needs to write it. A section that is there and
         * malformed is reported, on the same reasoning as a malformed configuration: a field
         * silently read as something else is a project quietly recording nowhere.
         */
        fun parse(text: String): UsageDatabaseConfig {
            if (text.isBlank()) return OFF

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            // A bare array at the top level is the configurations and nothing else, which parseAll
            // accepts and which has nowhere to put this section.
            if (root == null || root.isJsonNull || !root.isJsonObject) return OFF

            val section = root.asJsonObject.get(SECTION) ?: return OFF
            if (!section.isJsonObject) {
                throw AgentConfigurationException("\"$SECTION\" must be an object")
            }
            val entry = section.asJsonObject

            val url = entry.get(URL)?.let {
                if (it.isJsonPrimitive) it.asString.trim()
                else throw AgentConfigurationException("\"$SECTION\".$URL must be a URL in quotes")
            }.orEmpty()

            val enabled = entry.get(ENABLED)?.let {
                // Written as a JSON boolean, but read from the text as well, so `"true"` reads the
                // way it looks rather than as a typo that silently switches recording off.
                when (it.takeIf { element -> element.isJsonPrimitive }?.asString?.trim()?.lowercase()) {
                    "true", "yes", "on" -> true
                    "false", "no", "off" -> false
                    else -> throw AgentConfigurationException("\"$SECTION\".$ENABLED must be true or false")
                }
            } ?: true

            return UsageDatabaseConfig(url, enabled)
        }
    }
}
