package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Which header carries the token.
 *
 * Providers differ only in the header name, not in the request body, so this is enough to reach any
 * endpoint that speaks the Messages API -- Anthropic's own, a corporate gateway in front of it, or
 * Microsoft Foundry. Keeping it an enum rather than a free-text header name is what lets the token
 * stay in PasswordSafe: the secret is never part of the settings XML.
 */
enum class AuthScheme(val headerName: String, val displayName: String) {
    X_API_KEY("x-api-key", "x-api-key (Anthropic API)"),
    BEARER("Authorization", "Authorization: Bearer (OAuth, most gateways)"),
    API_KEY("api-key", "api-key (Microsoft Foundry)");

    fun headerValue(token: String): String = if (this == BEARER) "Bearer $token" else token
}

@Service(Service.Level.APP)
@State(name = "AnthropicChatSettings", storages = [Storage("anthropicChatSettings.xml")])
class AnthropicSettingsState : PersistentStateComponent<AnthropicSettingsState.State> {

    data class State(
        var model: String = "claude-sonnet-5",
        var maxTokens: Int = 1024,

        /**
         * The full URL of the messages endpoint, path included, rather than a base URL with the path
         * appended: provider paths do not agree (Foundry's base already ends in `/v1`, gateways add
         * prefixes of their own), so guessing at it would break more setups than it saves typing on.
         */
        var endpointUrl: String = DEFAULT_ENDPOINT_URL,
        var authScheme: AuthScheme = AuthScheme.X_API_KEY,
        var anthropicVersion: String = DEFAULT_ANTHROPIC_VERSION,

        /**
         * Extra headers as `Name: Value`, one per line. Stored in plain XML, so this is for routing
         * and tenancy headers a gateway needs -- never for secrets, which belong in the token field.
         */
        var extraHeaders: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

        fun getInstance(): AnthropicSettingsState = service()
    }
}
