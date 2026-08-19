package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool

class RunAtLocationTool(private val project: Project) : AICodingAgentTool {

    companion object {
        private const val MAX_ALTERNATIVES = 4
    }

    private val runner = ConfigurationRunner(project)

    override val interruptible = false

    override val name = "run_at_location"
    override val description =
        "Runs whatever is runnable at a source location -- a test class, a single test method, a " +
            "main function -- creating the configuration the way the gutter Run button does. Use " +
            "it when nothing is saved for it, the normal case for a test class you just wrote. A " +
            "line inside a method runs that method, a line on the class runs the class, no line " +
            "runs the file. Returns the same exit code, per-test results and output as " +
            "run_configuration. debug=true starts it under the debugger: set breakpoints with " +
            "toggle_breakpoint first, then call await_breakpoint."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/test/kotlin/FooTest.kt")
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "1-based line inside the declaration to run, matching what read_project_file " +
                        "returns. Anywhere inside it will do -- the signature line is the obvious " +
                        "choice. Omit to run the file as a whole.",
                )
            })
            add("debug", JsonObject().apply {
                addProperty("type", "boolean")
                addProperty(
                    "description",
                    "Start it under the debugger rather than just running it, as the gutter's Debug " +
                        "button does. Returns as soon as the debugger attaches, without waiting for " +
                        "the run to finish or reporting its results -- follow it with " +
                        "await_breakpoint. Defaults to false.",
                )
            })
            add("timeout_seconds", JsonObject().apply {
                addProperty("type", "integer")
                addProperty(
                    "description",
                    "How long to wait for it to finish before giving up on its output. It keeps " +
                        "running in the IDE either way. Defaults to " +
                        "${ConfigurationRunner.DEFAULT_TIMEOUT_SECONDS}, maximum " +
                        "${ConfigurationRunner.MAX_TIMEOUT_SECONDS}. With debug=true it means " +
                        "something different, because there is no output to wait for: how long to " +
                        "wait for the debugger to attach, defaulting to " +
                        "${ConfigurationRunner.DEFAULT_DEBUG_WAIT_SECONDS} with a maximum of " +
                        "${ConfigurationRunner.MAX_DEBUG_WAIT_SECONDS}.",
                )
            })
        })
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString?.trim().orEmpty()
        if (path.isEmpty()) return "Error: missing 'path'"

        val line = input.get("line")?.asInt
        val debug = input.get("debug")?.asBoolean ?: false

        val timeoutSeconds = if (debug) {
            (input.get("timeout_seconds")?.asInt ?: ConfigurationRunner.DEFAULT_DEBUG_WAIT_SECONDS)
                .coerceIn(1, ConfigurationRunner.MAX_DEBUG_WAIT_SECONDS)
        } else {
            (input.get("timeout_seconds")?.asInt ?: ConfigurationRunner.DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, ConfigurationRunner.MAX_TIMEOUT_SECONDS)
        }

        if (PsiTargets.resolveProjectPath(project, path) == null) {
            return "Error: path is outside the project directory"
        }

        val where = if (line != null) "$path:$line" else path
        val element = if (line != null) {
            PsiTargets.elementAtLine(project, path, line)
                ?: return "Error: no source at $where. Check the file exists and the line is within it."
        } else {
            PsiTargets.resolvePsiFile(project, path)
                ?: return "Error: could not open $path as a source file."
        }

        val produced = produce(element)
            ?: return "Error: nothing runnable at $where. The IDE offers a Run action only on " +
                "things it can execute -- a test class or method, a main function, a script. " +
                "Point at the declaration itself rather than at a call to it, and check the file " +
                "compiles: an unresolved test framework import leaves the producers with nothing " +
                "to recognise."

        val header = buildString {
            if (produced.reused) {
                append("Reused the existing ${produced.typeName} configuration \"${produced.settings.name}\".")
            } else {
                append("Created a temporary ${produced.typeName} configuration ")
                append("\"${produced.settings.name}\", as the gutter ")
                append(if (debug) "Debug" else "Run")
                append(" button would.")
            }
            if (produced.alternatives.isNotEmpty()) {
                append(" The producers also offered: ")
                append(produced.alternatives.take(MAX_ALTERNATIVES).joinToString(", "))
                append(".")
            }
        }

        val outcome = if (debug) {
            runner.debug(produced.settings, produced.settings.name, timeoutSeconds)
        } else {
            runner.run(produced.settings, produced.settings.name, timeoutSeconds)
        }
        return "$header\n\n$outcome"
    }

    private class Produced(
        val settings: RunnerAndConfigurationSettings,
        val typeName: String,
        val reused: Boolean,
        val alternatives: List<String>,
    )

    private fun produce(element: PsiElement): Produced? {
        var result: Produced? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = ReadAction.computeBlocking<Produced?, RuntimeException> { ask(element) }
        }
        return result
    }

    private fun ask(element: PsiElement): Produced? {
        val location = PsiLocation.fromPsiElement(project, element) ?: return null
        val context = ConfigurationContext.createEmptyContextForLocation(location)

        context.findExisting()?.let { existing ->
            return Produced(existing, existing.type.displayName, reused = true, alternatives = emptyList())
        }

        val candidates = context.configurationsFromContext.orEmpty()
        val best = candidates.firstOrNull() ?: return null
        val settings = best.configurationSettings

        RunManager.getInstance(project).setTemporaryConfiguration(settings)

        return Produced(
            settings,
            best.configurationType.displayName,
            reused = false,
            alternatives = candidates.drop(1).map { describe(it) },
        )
    }

    private fun describe(candidate: ConfigurationFromContext): String =
        "${candidate.configurationSettings.name} (${candidate.configurationType.displayName})"
}
