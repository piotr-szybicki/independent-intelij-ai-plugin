package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

/**
 * Starts an existing run configuration under the debugger, by name.
 *
 * Deliberately only launches what is already there. A remote debug configuration encodes a host, a
 * port and a source mapping that have to line up with however the user started their JVM -- none of
 * which is inferable from the chat, so the configuration is the user's to prepare and this tool's
 * job is only to press Debug on it. When nothing suitable exists, [RunAtLocationTool] with
 * `debug` has the platform produce a configuration from a source location instead.
 *
 * The launch itself is [ConfigurationRunner]'s job.
 *
 * Pairs with `await_breakpoint`: set the breakpoint, start the configuration here, then wait.
 */
class StartDebugConfigurationTool(private val project: Project) : AICodingAgentTool {

    companion object {
        /** Enough names to recognise a typo without turning an error into a wall of text. */
        private const val MAX_LISTED = 40
    }

    private val runner = ConfigurationRunner(project)

    override val name = "start_debug_configuration"
    override val description =
        "Starts one of the project's existing run configurations under the debugger, by name -- " +
            "including Remote JVM Debug configurations, which attach to an already-running process. " +
            "The configuration must already exist; naming one that does not returns the list of the " +
            "ones that do. To debug a test class or method that has no configuration yet, use " +
            "run_at_location with debug=true instead. Follow this with await_breakpoint to catch the hit."
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
            add("wait_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for the debugger to actually attach before reporting back. " +
                        "Defaults to ${ConfigurationRunner.DEFAULT_DEBUG_WAIT_SECONDS}, maximum " +
                        "${ConfigurationRunner.MAX_DEBUG_WAIT_SECONDS}.",
                )
            })
        })
        add("required", JsonArray().apply { add("name") })
    }

    override fun execute(input: JsonObject): String {
        val name = input.get("name")?.asString?.trim().orEmpty()
        if (name.isEmpty()) return "Error: missing 'name'"

        val waitSeconds = (input.get("wait_seconds")?.asInt ?: ConfigurationRunner.DEFAULT_DEBUG_WAIT_SECONDS)
            .coerceIn(1, ConfigurationRunner.MAX_DEBUG_WAIT_SECONDS)

        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(name)
            ?: return notFound(name, runManager.allSettings)

        return runner.debug(settings, name, waitSeconds)
    }

    private fun notFound(name: String, all: List<RunnerAndConfigurationSettings>): String {
        if (all.isEmpty()) {
            return "Error: the project has no run configurations. Use run_at_location with " +
                "debug=true to debug a test class or method directly, or ask the user to create a " +
                "configuration (Run | Edit Configurations) and tell you its name."
        }

        val names = all.map { it.name }
        val listed = names.take(MAX_LISTED).joinToString("\n") { "  $it" }
        val more = if (names.size > MAX_LISTED) "\n  ... and ${names.size - MAX_LISTED} more" else ""
        return "Error: no run configuration named \"$name\". The project has:\n$listed$more\n\n" +
            "If you were trying to debug a test class or method, use run_at_location with its file " +
            "and line and debug=true instead -- that produces the configuration the way the " +
            "editor's gutter Debug button does."
    }
}
