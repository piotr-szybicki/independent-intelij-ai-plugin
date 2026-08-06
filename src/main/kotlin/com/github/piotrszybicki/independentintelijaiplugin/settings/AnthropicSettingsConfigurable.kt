package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AnthropicSettingsConfigurable : Configurable {

    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val endpointField = JBTextField()
    private val anthropicVersionField = JBTextField()

    private val authSchemeCombo = ComboBox(DefaultComboBoxModel(AuthScheme.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { scheme -> scheme.displayName }
    }

    private val extraHeadersArea = JBTextArea(4, 40).apply {
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", font.size)
    }

    override fun getDisplayName(): String = "Anthropic Chat"

    override fun createComponent(): JComponent = panel {
        group("Connection") {
            row("Endpoint URL:") {
                cell(endpointField).align(AlignX.FILL)
            }.rowComment(
                "The full messages endpoint, path included. Anthropic's own is " +
                    "<code>${AnthropicSettingsState.DEFAULT_ENDPOINT_URL}</code>. Point this at a " +
                    "gateway or proxy to route through it, as long as it speaks the Messages API.",
            )
            row("API token:") {
                cell(apiKeyField).align(AlignX.FILL)
            }.rowComment("Stored in the IDE's password safe, not in settings files.")
            row("Send token as:") {
                cell(authSchemeCombo).align(AlignX.FILL)
            }
            row("anthropic-version:") {
                cell(anthropicVersionField).align(AlignX.FILL)
            }.rowComment("Leave empty to omit the header, which some gateways require.")
            row("Extra headers:") {
                cell(extraHeadersArea).align(AlignX.FILL)
            }.rowComment(
                "One <code>Name: Value</code> per line, for routing or tenancy headers a gateway " +
                    "needs. Stored in plain text &mdash; put secrets in the API token field instead.",
            )
        }
        group("Model") {
            row("Model:") {
                cell(modelField).align(AlignX.FILL)
            }
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val settings = AnthropicSettingsState.getInstance().state
        return String(apiKeyField.password) != (AnthropicCredentials.apiKey ?: "") ||
            modelField.text != settings.model ||
            endpointField.text != settings.endpointUrl ||
            anthropicVersionField.text != settings.anthropicVersion ||
            extraHeadersArea.text != settings.extraHeaders ||
            authSchemeCombo.selectedItem != settings.authScheme
    }

    override fun apply() {
        AnthropicCredentials.apiKey = String(apiKeyField.password)

        val settings = AnthropicSettingsState.getInstance().state
        settings.model = modelField.text.trim().ifBlank { "claude-sonnet-5" }
        // Blanking the endpoint means "back to Anthropic" rather than an unusable configuration.
        settings.endpointUrl = endpointField.text.trim()
            .ifBlank { AnthropicSettingsState.DEFAULT_ENDPOINT_URL }
        settings.anthropicVersion = anthropicVersionField.text.trim()
        settings.extraHeaders = extraHeadersArea.text
        settings.authScheme = authSchemeCombo.selectedItem as? AuthScheme ?: AuthScheme.X_API_KEY
    }

    override fun reset() {
        val settings = AnthropicSettingsState.getInstance().state
        apiKeyField.text = AnthropicCredentials.apiKey ?: ""
        modelField.text = settings.model
        endpointField.text = settings.endpointUrl
        anthropicVersionField.text = settings.anthropicVersion
        extraHeadersArea.text = settings.extraHeaders
        authSchemeCombo.selectedItem = settings.authScheme
    }
}
