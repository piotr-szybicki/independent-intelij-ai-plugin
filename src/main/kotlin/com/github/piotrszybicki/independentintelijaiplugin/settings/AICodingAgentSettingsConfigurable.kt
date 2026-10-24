package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.logging.ModelUsageDatabase
import com.github.piotrszybicki.independentintelijaiplugin.toolWindow.ChatToolWindowFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

class AICodingAgentSettingsConfigurable : Configurable {
    private val configurationStatus = JBLabel()

    private val configurationText =
        JBTextArea(24, 80).apply {
            isEditable = false
            lineWrap = false
            font = JBUI.Fonts.create("Monospaced", font.size)
        }

    private var loadedDatabase = AgentConfigurations.LoadedDatabase(UsageDatabaseConfig.OFF, null)

    override fun getDisplayName(): String = "AICodingAgent"

    override fun createComponent(): JComponent =
        panel {
            row {
                button("Reload Configuration") { reloadConfiguration() }.component.name =
                    "settings.reloadConfigurationButton"
                button("Test MySQL Connection") { testUsageDatabase() }.component.name =
                    "settings.testMySqlButton"
            }
            row { cell(configurationStatus).align(AlignX.FILL) }
            row { scrollCell(configurationText).align(AlignX.FILL) }
        }.also {
            it.name = "settings.root"
            reloadConfiguration()
        }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = reloadConfiguration()

    private fun reloadConfiguration() {
        val project = project()
        if (project == null) {
            configurationText.text = ""
            showProblem("Open a project to view its configuration.")
            return
        }

        val configurations = AgentConfigurations.getInstance(project)
        configurationText.text = configurations.text().orEmpty()
        val problem = configurations.unavailableReason
        if (problem != null) {
            showProblem(problem)
        } else {
            configurationStatus.text = configurations.path.toString()
            configurationStatus.foreground = UIUtil.getLabelForeground()
        }

        val previous = loadedDatabase.database
        loadedDatabase = configurations.usageDatabase()
        if (previous != loadedDatabase.database) ModelUsageDatabase.close()
        ChatToolWindowFactory.refreshProviderBars()
    }

    private fun showProblem(message: String) {
        configurationStatus.text = message
        configurationStatus.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
    }

    private fun testUsageDatabase() {
        reloadConfiguration()
        loadedDatabase.error?.let {
            Messages.showErrorDialog(it, "MySQL Connection")
            return
        }
        val url = loadedDatabase.database.url
        if (url.isBlank()) {
            Messages.showInfoMessage(
                "No database URL is configured in ${AgentConfiguration.FILE_NAME}.",
                "MySQL Connection",
            )
            return
        }

        val task =
            object : Task.Modal(project(), "Connecting to MySQL", true) {
                var report = ""

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    report = ModelUsageDatabase.test(url)
                }

                override fun onSuccess() = Messages.showInfoMessage(report, "MySQL Connection")
            }
        ProgressManager.getInstance().run(task)
    }

    private fun project(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()
}
