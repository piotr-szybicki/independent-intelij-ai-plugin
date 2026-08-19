package com.github.piotrszybicki.independentintelijaiplugin.logging

internal data class JdbcUrl(val serverUrl: String, val database: String) {

    companion object {

        private const val PREFIX = "jdbc:mysql://"

        private val ENV_REFERENCE = Regex("""\$\{env:([A-Za-z_][A-Za-z0-9_]*)}""")

        private val PLAIN_IDENTIFIER = Regex("""[A-Za-z0-9_$]+""")

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

        fun parse(url: String): JdbcUrl {
            require(url.startsWith(PREFIX)) { "a MySQL URL has to start with $PREFIX" }

            val rest = url.removePrefix(PREFIX)
            val query = rest.substringAfter('?', "")
            val path = rest.substringBefore('?')
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
