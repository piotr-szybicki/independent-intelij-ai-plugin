package com.github.piotrszybicki.independentintelijaiplugin.settings

/**
 * Where requests go when there is no configuration file to say.
 *
 * Narrower than it once was, and deliberately. It used to beat the configured URL outright, which
 * made sense while there was one URL to beat: pointing a run at a gateway was a property of how the
 * IDE was launched rather than of the project. Against a file of entries it stopped making sense --
 * it replaced the URL of whichever entry was picked while that entry's protocol, token header and
 * token stayed behind, describing a provider the URL no longer pointed at. A Foundry entry with the
 * variable set to Anthropic's URL is not an override, it is a request that cannot be sent.
 *
 * So it now says only what [AgentConfiguration.fallback] runs on -- the built-in configuration used
 * when the file is missing, empty or unreadable, where there is no entry for it to contradict.
 * Switching provider is the dropdown, and the URL is the entry's own.
 */
object EndpointUrl {

    /** The environment variable the endpoint URL is read from. */
    const val ENV_VAR = "AI_API_URL"

    /**
     * The URL the environment names, or null when [ENV_VAR] is unset or blank.
     *
     * Read on every access rather than cached, like the token: a GUI-launched IDE only ever sees the
     * environment it was started with, so there is nothing to gain by holding on to the value and a
     * stale one to lose by it.
     */
    val fromEnvironment: String?
        get() = System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }

    /** The URL a request should go to, resolved per turn so a chat never runs on a stale one. */
    fun resolve(configured: String): String = resolve(fromEnvironment, configured)

    /** The precedence on its own, so it can be tested without a running IDE behind it. */
    fun resolve(environment: String?, configured: String): String =
        environment?.trim()?.takeIf { it.isNotBlank() }
            ?: configured.trim().ifBlank { AgentConfiguration.DEFAULT_ENDPOINT_URL }
}
