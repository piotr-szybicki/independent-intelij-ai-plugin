package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class RunConfigurationTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_LISTED = 40
    }

    private val runner = ConfigurationRunner(project)

    override val interruptible = false

    override val name = "run_configuration"
    override val description =
        "Runs an existing run configuration by name -- tests, an application, a Gradle or Maven " +
            "task -- and returns its exit code and console output. Naming one that does not exist " +
            "returns the ones that do. For a test with no configuration yet, use run_at_location; " +
            "to stop at a breakpoint, start_debug_configuration."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("name", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "Name of the run configuration exactly as it appears in the IDE's run configuration list",
                )
            })
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for it to finish before giving up on its output. It keeps " +
                        "running in the IDE either way. Defaults to " +
                        "${ConfigurationRunner.DEFAULT_TIMEOUT_SECONDS}, maximum " +
                        "${ConfigurationRunner.MAX_TIMEOUT_SECONDS}.",
                )
            })
        })
        add("required", JsonArray().apply { add("name") })
    }

    override fun execute(input: JsonObject): String {
        val configurationName = input.get("name")?.asString?.trim().orEmpty()
        if (configurationName.isEmpty()) return "Error: missing 'name'"

        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: ConfigurationRunner.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, ConfigurationRunner.MAX_TIMEOUT_SECONDS)

        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(configurationName)
            ?: return notFound(configurationName, runManager.allSettings)

        return runner.run(settings, configurationName, timeoutSeconds)
    }

    private fun notFound(name: String, all: List<RunnerAndConfigurationSettings>): String {
        if (all.isEmpty()) {
            return "Error: the project has no run configurations. Use run_at_location to run a " +
                "test class or method directly, or ask the user to create a configuration " +
                "(Run | Edit Configurations) and tell you its name."
        }

        val names = all.map { it.name }
        val listed = names.take(MAX_LISTED).joinToString("\n") { "  $it" }
        val more = if (names.size > MAX_LISTED) "\n  ... and ${names.size - MAX_LISTED} more" else ""
        return "Error: no run configuration named \"$name\". The project has:\n$listed$more\n\n" +
            "If you were trying to run a test class or method, use run_at_location with its file " +
            "and line instead -- that produces the configuration the way the editor's gutter " +
            "Run button does."
    }
}
