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
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool

/**
 * The editor gutter's Run button, as a tool.
 *
 * That green arrow is not "run this test" -- it asks every registered `RunConfigurationProducer`
 * what it can build from the element under the caret, takes the best answer, and runs it as a
 * *temporary* configuration. This does the same thing from a file and line, which is what makes it
 * possible to run a test that has no saved configuration: [RunConfigurationTool] can only launch
 * what already exists, and most test classes never get an entry until someone clicks the gutter.
 *
 * The producers themselves come from whatever plugins the IDE has -- JUnit, Gradle, pytest. None of
 * them are referenced here, so this needs no extra plugin dependency and degrades to "nothing
 * runnable here" in an IDE that has no producer for the language in question.
 */
class RunAtLocationTool(private val project: Project) : AnthropicTool {

    companion object {
        /** Enough to show what else the producers offered without burying the result. */
        private const val MAX_ALTERNATIVES = 4
    }

    private val runner = ConfigurationRunner(project)

    /** Same as [RunConfigurationTool]: the process outlives this thread, in the IDE's Run window. */
    override val interruptible = false

    override val name = "run_at_location"
    override val description =
        "Runs whatever is runnable at a place in the source -- a test class, a single test method, " +
            "a main function -- creating the run configuration for it the way the editor's gutter " +
            "Run button does. Use this when there is no saved run configuration for what you want " +
            "to run, which is the normal case for a test class you just wrote. Give the line of " +
            "the declaration you want to run: a line inside a test method runs that one method, a " +
            "line on the class runs the whole class, and omitting the line runs the file. Returns " +
            "the same exit code, per-test results and console output as run_configuration. Prefer " +
            "run_configuration when a configuration for this already exists."
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
        add("required", JsonArray().apply { add("path") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString?.trim().orEmpty()
        if (path.isEmpty()) return "Error: missing 'path'"

        val line = input.get("line")?.asInt
        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: ConfigurationRunner.DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, ConfigurationRunner.MAX_TIMEOUT_SECONDS)

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
                append("\"${produced.settings.name}\", as the gutter Run button would.")
            }
            if (produced.alternatives.isNotEmpty()) {
                append(" The producers also offered: ")
                append(produced.alternatives.take(MAX_ALTERNATIVES).joinToString(", "))
                append(".")
            }
        }

        return "$header\n\n" + runner.run(produced.settings, produced.settings.name, timeoutSeconds)
    }

    private class Produced(
        val settings: RunnerAndConfigurationSettings,
        val typeName: String,
        /** True when a saved configuration already covered this location and was used as-is. */
        val reused: Boolean,
        val alternatives: List<String>,
    )

    /**
     * Asks the platform what it can run at [element].
     *
     * On the EDT because that is where the gutter action does it and some producers assume it, and
     * inside a read action because they all walk PSI.
     */
    private fun produce(element: PsiElement): Produced? {
        var result: Produced? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = ReadAction.compute<Produced?, RuntimeException> { ask(element) }
        }
        return result
    }

    /** The body of [produce]; separate only so it runs inside a read action without nesting. */
    private fun ask(element: PsiElement): Produced? {
        val location = PsiLocation.fromPsiElement(project, element) ?: return null
        val context = ConfigurationContext.createEmptyContextForLocation(location)

        // Reuse before create: without this, every call mints another configuration for a test that
        // already has a perfectly good one.
        context.findExisting()?.let { existing ->
            return Produced(existing, existing.type.displayName, reused = true, alternatives = emptyList())
        }

        // Ordered by the platform's own preference comparator, so the first is what the gutter would
        // have run. The rest are worth naming: a Gradle project typically offers both a Gradle and a
        // JUnit way to run the same test.
        val candidates = context.configurationsFromContext.orEmpty()
        val best = candidates.firstOrNull() ?: return null
        val settings = best.configurationSettings

        // Temporary, i.e. italic in the run dropdown and evicted automatically once enough pile up
        // -- the same status the gutter gives it, so this does not slowly fill the user's
        // configuration list with one entry per test we ran.
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
