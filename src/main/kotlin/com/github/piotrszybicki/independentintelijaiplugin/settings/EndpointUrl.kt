package com.github.piotrszybicki.independentintelijaiplugin.settings

/**
 * Where requests go, from the three places it can be said, in the order they win.
 *
 * The environment beats the saved setting for the same reason the token does -- see
 * [AICodingAgentCredentials]. Which gateway or proxy a run goes through is a property of how the IDE
 * was launched rather than of the project, and putting it in [ENV_VAR] should be enough to take
 * effect without the settings page being edited to match.
 *
 * The session override is the way back out of that. Without it the endpoint field would be a
 * read-only display whenever the variable is set -- whatever it saved would never be read again --
 * so an edit made while the variable is in force is kept here instead: in memory, for this IDE
 * session, and never written to the settings XML. The next launch reads the environment afresh.
 */
object EndpointUrl {

    /** The environment variable the endpoint URL is read from. */
    const val ENV_VAR = "AI_API_URL"

    /**
     * Written from the settings page on the EDT and read from the agent's pooled thread on every
     * turn, which is what the volatile is for.
     */
    @Volatile
    private var sessionOverride: String? = null

    /**
     * The URL the environment names, or null when [ENV_VAR] is unset or blank.
     *
     * Read on every access rather than cached, like the token: a GUI-launched IDE only ever sees the
     * environment it was started with, so there is nothing to gain by holding on to the value and a
     * stale one to lose by it.
     */
    val fromEnvironment: String?
        get() = System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotBlank() }

    /** The URL typed on the settings page this session, or null while nothing overrides. */
    val sessionUrl: String?
        get() = sessionOverride

    /** Takes an override for the rest of this IDE session. Null or blank gives the override up. */
    fun overrideForSession(url: String?) {
        sessionOverride = url?.trim()?.takeIf { it.isNotBlank() }
    }

    /** The URL a request should go to, resolved per turn so a chat never runs on a stale one. */
    fun resolve(): String = resolve(
        override = sessionOverride,
        environment = fromEnvironment,
        configured = AICodingAgentSettingsState.getInstance().state.endpointUrl,
    )

    /** The precedence on its own, so it can be tested without a running IDE behind it. */
    fun resolve(override: String?, environment: String?, configured: String): String =
        override?.trim()?.takeIf { it.isNotBlank() }
            ?: environment?.trim()?.takeIf { it.isNotBlank() }
            ?: configured.trim().ifBlank { AICodingAgentSettingsState.DEFAULT_ENDPOINT_URL }
}
