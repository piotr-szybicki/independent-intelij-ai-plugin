package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.InplaceButton
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.github.piotrszybicki.independentintelijaiplugin.settings.AICodingAgentSettingsState
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfigurations
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

internal class ProviderBar(
    private val project: Project,
    private val onStatus: (String) -> Unit,
) {
    private var refilling = false
    private var providers: List<AgentConfiguration> = emptyList()

    var configurationName: String = ""
        private set
    var modelName: String = ""
        private set

    private val providerCombo = ComboBox(DefaultComboBoxModel<String>()).apply {
        font = JBFont.small()
        toolTipText = "Which provider in ${AgentConfiguration.FILE_NAME} to send to"
        addActionListener { if (!refilling) providerChosen() }
    }

    private val modelCombo = ComboBox(DefaultComboBoxModel<String>()).apply {
        font = JBFont.small()
        toolTipText = "Which model to ask this provider for"
        addActionListener { if (!refilling) modelChosen() }
    }

    val component: JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(ChatColors.separator),
            JBUI.Borders.empty(4, 8),
        )
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(providerCombo)
                add(modelCombo)
            },
            BorderLayout.CENTER,
        )
        add(
            InplaceButton("Re-read ${AgentConfiguration.FILE_NAME}", AllIcons.Actions.Refresh) {
                refresh()
            },
            BorderLayout.EAST,
        )
    }

    fun seedFromDefault() {
        val settings = AICodingAgentSettingsState.getInstance().state
        configurationName = settings.activeConfiguration
        modelName = settings.activeModel
        refresh()
    }

    fun setSelection(configuration: String?, model: String?) {
        configuration?.let { configurationName = it }
        model?.let { modelName = it }
        refresh()
    }

    fun refresh() {
        refilling = true
        try {
            providers = AgentConfigurations.getInstance(project).load().configurations
            val active = AgentConfigurations.select(providers, configurationName)
                ?: AgentConfiguration.fallback()

            providerCombo.model = DefaultComboBoxModel(providers.map { it.name }.toTypedArray())
            providerCombo.isEnabled = providers.isNotEmpty()
            providerCombo.selectedItem = active.name

            modelCombo.model = DefaultComboBoxModel(active.models.toTypedArray())
            modelCombo.isEnabled = active.models.isNotEmpty()
            modelCombo.selectedItem = active.withModel(modelName).model
        } finally {
            refilling = false
        }
    }

    private fun providerChosen() {
        val chosen = providers.firstOrNull { it.name == providerCombo.selectedItem } ?: return
        if (chosen.name == configurationName) return
        configurationName = chosen.name
        modelName = ""
        rememberAsDefault()
        refresh()
        onStatus("Sending to ${chosen.name} (${chosen.model}) from the next message.")
    }

    private fun modelChosen() {
        val chosen = modelCombo.selectedItem as? String ?: return
        if (chosen == modelName) return
        modelName = chosen
        rememberAsDefault()
        onStatus("Asking for $chosen from the next message.")
    }

    private fun rememberAsDefault() {
        val settings = AICodingAgentSettingsState.getInstance().state
        settings.activeConfiguration = configurationName
        settings.activeModel = modelName
    }
}
