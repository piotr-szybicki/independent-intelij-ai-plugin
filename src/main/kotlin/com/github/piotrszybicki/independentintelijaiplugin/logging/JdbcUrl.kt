package com.github.piotrszybicki.independentintelijaiplugin.logging

/**
 * The MySQL URL from the settings, taken apart far enough to be used.
 *
 * Two things need doing to it that the driver will not do. The database has to be creatable, which
 * means reaching the server without naming it -- so the name is split off and a server-only URL is
 * built beside it. And the timeouts have to be set, because the driver's defaults let a host that is
 * routable but silent hold a thread for minutes.
 *
 * Parsed by hand rather than with `URI`: a JDBC URL is not one. `jdbc:mysql://...` puts the whole of
 * the real URL in `URI`'s opaque scheme-specific part, and the failover form with two hosts before
 * the slash is not a valid authority at all.
 */
internal data class JdbcUrl(val serverUrl: String, val database: String) {

    companion object {

        private const val PREFIX = "jdbc:mysql://"

        /**
         * Placeholder for a value that must not be written to the settings XML -- the password, most
         * of the time. The same reasoning as [com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentCredentials]:
         * secrets live in the environment, and the IDE only sees what was set before it launched.
         */
        private val ENV_REFERENCE = Regex("""\$\{env:([A-Za-z_][A-Za-z0-9_]*)}""")

        /**
         * What a database name is allowed to be.
         *
         * Deliberately narrower than MySQL's own rule, which permits nearly anything inside
         * backticks. The name is interpolated into `CREATE DATABASE` -- it cannot be a bound
         * parameter, DDL has no parameters -- so the guarantee that matters is that it can never
         * carry a backtick out of the URL and end the quoting early.
         */
        private val PLAIN_IDENTIFIER = Regex("""[A-Za-z0-9_$]+""")

        /**
         * The URL to actually connect with: expanded, checked, and with [defaults] filled in where
         * the URL does not set them itself.
         *
         * Empty for an empty field, which is how "configured but not filled in" stays quiet rather
         * than warning once per request.
         */
        fun prepare(raw: String, defaults: Map<String, String>): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""

            val expanded = expandEnv(trimmed)
            require(expanded.startsWith(PREFIX)) { "a MySQL URL has to start with $PREFIX" }

            val existing = expanded.substringAfter('?', "")
                .split('&')
                .mapNotNull { it.substringBefore('=').takeIf(String::isNotEmpty) }
                .toSet()
            val missing = defaults.filterKeys { it !in existing }
            if (missing.isEmpty()) return expanded

            val separator = if (expanded.contains('?')) "&" else "?"
            return expanded + separator + missing.entries.joinToString("&") { "${it.key}=${it.value}" }
        }

        /** Splits [url] into the server to connect to and the database to create on it. */
        fun parse(url: String): JdbcUrl {
            require(url.startsWith(PREFIX)) { "a MySQL URL has to start with $PREFIX" }

            val rest = url.removePrefix(PREFIX)
            val query = rest.substringAfter('?', "")
            val path = rest.substringBefore('?')
            // Everything before the first slash, which is host:port -- or several of them, since the
            // failover form lists hosts comma-separated in exactly that position.
            val authority = path.substringBefore('/')
            val database = path.substringAfter('/', "")

            require(authority.isNotEmpty()) { "the URL names no host" }
            require(database.isEmpty() || PLAIN_IDENTIFIER.matches(database)) {
                "`$database` is not a usable database name -- letters, digits, _ and $ only"
            }

            val serverUrl = PREFIX + authority + "/" + if (query.isEmpty()) "" else "?$query"
            return JdbcUrl(serverUrl, database)
        }

        private fun expandEnv(value: String): String = ENV_REFERENCE.replace(value) { match ->
            val variable = match.groupValues[1]
            System.getenv(variable)
                ?: throw IllegalArgumentException("the environment variable $variable is not set in the IDE's environment")
        }
    }
}
