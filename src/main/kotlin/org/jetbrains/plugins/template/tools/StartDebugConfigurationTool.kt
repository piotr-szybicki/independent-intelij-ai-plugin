package org.jetbrains.plugins.template.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import org.jetbrains.plugins.template.anthropic.AnthropicTool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts an existing run configuration under the debugger, by name.
 *
 * Deliberately only launches what is already there. A remote debug configuration encodes a host, a
 * port and a source mapping that have to line up with however the user started their JVM -- none of
 * which is inferable from the chat, so the configuration is the user's to prepare and this tool's
 * job is only to press Debug on it.
 *
 * Pairs with `await_breakpoint`: set the breakpoint, start the configuration here, then wait.
 */
class StartDebugConfigurationTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_WAIT_SECONDS = 20
        private const val MAX_WAIT_SECONDS = 120

        /** Enough names to recognise a typo without turning an error into a wall of text. */
        private const val MAX_LISTED = 40
    }

    override val name = "start_debug_configuration"
    override val description =
        "Starts one of the project's existing run configurations under the debugger, by name -- " +
            "including Remote JVM Debug configurations, which attach to an already-running process. " +
            "The configuration must already exist; this tool cannot create or edit one, so ask the " +
            "user to set it up and tell you its name. Naming a configuration that does not exist " +
            "returns the list of the ones that do. Follow this with await_breakpoint to catch the hit."
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
                        "Defaults to $DEFAULT_WAIT_SECONDS, maximum $MAX_WAIT_SECONDS.",
                )
            })
        })
        add("required", JsonArray().apply { add("name") })
    }

    override fun execute(input: JsonObject): String {
        val name = input.get("name")?.asString?.trim().orEmpty()
        if (name.isEmpty()) return "Error: missing 'name'"

        val waitSeconds = (input.get("wait_seconds")?.asInt ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(1, MAX_WAIT_SECONDS)

        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(name)
            ?: return notFound(name, runManager.allSettings)

        val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()

        val started = AtomicReference<XDebugSession?>()
        val attached = CountDownLatch(1)
        val scope = Disposer.newDisposable("start_debug_configuration")

        try {
            // Subscribed before the launch: attaching to an already-running JVM can complete before
            // executeConfiguration has even returned.
            project.messageBus.connect(scope).subscribe(
                XDebuggerManager.TOPIC,
                object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        if (started.compareAndSet(null, debugProcess.session)) attached.countDown()
                    }
                },
            )

            var failure: Exception? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    ProgramRunnerUtil.executeConfiguration(settings, debugExecutor)
                } catch (e: Exception) {
                    failure = e
                }
            }
            failure?.let { return "Error: could not start \"$name\": ${it.message}" }

            if (!attached.await(waitSeconds.toLong(), TimeUnit.SECONDS)) {
                // executeConfiguration reports its own failures in the Run window, not to the
                // caller, so a missing session is the only signal available here.
                return "Started \"$name\", but no debug session appeared within ${waitSeconds}s. " +
                    "For a remote configuration this usually means nothing is listening on the " +
                    "configured host and port -- check that the target process is running with the " +
                    "matching -agentlib:jdwp options. The IDE's Debug tool window has the real error."
            }
        } finally {
            Disposer.dispose(scope)
        }

        val session = started.get()
        return "Debugger attached: \"$name\" is running as session \"${session?.sessionName ?: name}\". " +
            "Call await_breakpoint to wait for it to stop."
    }

    private fun notFound(name: String, all: List<RunnerAndConfigurationSettings>): String {
        if (all.isEmpty()) {
            return "Error: the project has no run configurations. Ask the user to create one " +
                "(Run | Edit Configurations) and tell you its name."
        }

        val names = all.map { it.name }
        val listed = names.take(MAX_LISTED).joinToString("\n") { "  $it" }
        val more = if (names.size > MAX_LISTED) "\n  ... and ${names.size - MAX_LISTED} more" else ""
        return "Error: no run configuration named \"$name\". The project has:\n$listed$more"
    }
}
