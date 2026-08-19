package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class StartDebugConfigurationTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_LISTED = 40
    }

    private val runner = ConfigurationRunner(project)

    override val name = "start_debug_configuration"
    override val description =
        "Starts an existing run configuration under the debugger by name, including Remote JVM " +
            "Debug configurations that attach to a running process. Naming one that does not " +
            "exist returns the ones that do. When nothing saved covers what you want to debug, " +
            "start the process yourself with run_shell_command under " +
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 and attach to " +
            "it with a Remote JVM Debug configuration on that port. Follow with await_breakpoint."
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
            return "Error: the project has no run configurations. Debugging needs one to attach " +
                "with: ask the user to create a Remote JVM Debug configuration " +
                "(Run | Edit Configurations) and tell you its name, then start the process " +
                "yourself with run_shell_command under the matching -agentlib:jdwp options."
        }

        val names = all.map { it.name }
        val listed = names.take(MAX_LISTED).joinToString("\n") { "  $it" }
        val more = if (names.size > MAX_LISTED) "\n  ... and ${names.size - MAX_LISTED} more" else ""
        return "Error: no run configuration named \"$name\". The project has:\n$listed$more\n\n" +
            "If you were trying to debug a test class or method, run it with run_shell_command " +
            "under -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 -- a " +
            "short timeout_seconds is enough, since it waits for a debugger and keeps running in " +
            "the terminal -- then attach to it with a Remote JVM Debug configuration on that port."
    }
}
