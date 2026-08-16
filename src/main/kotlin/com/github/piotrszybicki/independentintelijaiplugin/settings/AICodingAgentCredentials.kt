package com.github.piotrszybicki.independentintelijaiplugin.settings

/**
 * The environment variable the starter configuration file points its token at.
 *
 * A default rather than the rule: since [AgentConfiguration] arrived, each configuration says where
 * its own token comes from, and a file with three providers in it needs three variables. This is the
 * one [AgentConfiguration.DEFAULT] and the Anthropic starter entry name, so a setup that predates
 * the file keeps working without its token being moved anywhere.
 */
object AICodingAgentCredentials {

    /** The environment variable the API token is read from by default. */
    const val ENV_VAR = "AI_API_KEY"

    /**
     * The token that variable holds, or null when it is unset or blank.
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
