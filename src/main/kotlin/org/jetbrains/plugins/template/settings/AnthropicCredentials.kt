package org.jetbrains.plugins.template.settings

object AnthropicCredentials {

    /** The environment variable the API token is read from. */
    const val ENV_VAR = "AI_API_KEY"

    /**
     * The API token, taken from the [ENV_VAR] environment variable, or null when it is unset or blank.
     *
     * Read on every access rather than cached: the value is cheap to fetch, and caching would only
     * mean a stale token surviving until the IDE restarts.
     *
     * Note that a GUI-launched IDE inherits the environment it was started with, so a variable
     * exported in a shell profile is not visible unless the IDE was launched from that shell.
     */
    val apiKey: String?
        get() = System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }
}
