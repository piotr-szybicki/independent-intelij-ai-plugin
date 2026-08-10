package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Runs one of the project's existing run configurations and returns its exit code and output.
 *
 * This is the Run counterpart to [StartDebugConfigurationTool], and the same reasoning applies to
 * why it only launches what already exists: a configuration encodes a module, a classpath, VM
 * options and environment that are the user's to set up, not something inferable from the chat.
 * When there is no configuration for what needs running, [RunAtLocationTool] has the platform
 * produce one from a source location instead.
 *
 * The launch itself is [ConfigurationRunner]'s job.
 *
 * Unlike `run_shell_command` this needs no approval prompt: a run configuration is something the
 * user already created and can already launch with one click, so there is nothing here they have
 * not already sanctioned.
 */
class RunConfigurationTool(private val project: Project) : AICodingAgentTool {

    companion object {
        /** Enough names to recognise a typo without turning an error into a wall of text. */
        private const val MAX_LISTED = 40
    }

    private val runner = ConfigurationRunner(project)

    /**
     * The process belongs to the IDE's Run window, not to this thread: interrupting the wait would
     * leave it running with nobody reading its output. The Stop button in the Run window is the way
     * to stop it.
     */
    override val interruptible = false

    override val name = "run_configuration"
    override val description =
        "Runs one of the project's existing run configurations -- a test configuration, an " +
            "application, a Gradle or Maven task -- and returns its exit code and console output. " +
            "Use this to run tests and read back which ones failed. The configuration must already " +
            "exist; naming one that does not returns the list of the ones that do. To run a test " +
            "class or method that has no configuration yet, use run_at_location instead. When you " +
            "need to stop at a breakpoint, use start_debug_configuration, or run_at_location with " +
            "debug=true if there is no configuration for it."
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
