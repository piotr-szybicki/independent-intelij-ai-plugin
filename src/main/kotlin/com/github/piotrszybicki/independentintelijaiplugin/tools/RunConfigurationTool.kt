package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.github.piotrszybicki.independentintelijaiplugin.anthropic.AnthropicTool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one of the project's existing run configurations and returns its exit code and output.
 *
 * This is the Run counterpart to [StartDebugConfigurationTool], and the same reasoning applies to
 * why it only launches what already exists: a configuration encodes a module, a classpath, VM
 * options and environment that are the user's to set up, not something inferable from the chat.
 *
 * The output is read by attaching a [ProcessListener] to the launched process rather than by
 * scraping the Run window, so the exit code is the real one. That matters most for test
 * configurations, where the exit code is the pass/fail signal and the console text carries the
 * failure detail -- running the tests and reading back which ones failed is the point of this tool.
 *
 * Unlike `run_shell_command` this needs no approval prompt: a run configuration is something the
 * user already created and can already launch with one click, so there is nothing here they have
 * not already sanctioned.
 */
class RunConfigurationTool(private val project: Project) : AnthropicTool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 180
        private const val MAX_TIMEOUT_SECONDS = 900

        /** Test output is frequently enormous and the failure summary is at the end. */
        private const val MAX_OUTPUT_CHARS = 20_000

        /** Enough names to recognise a typo without turning an error into a wall of text. */
        private const val MAX_LISTED = 40

        /** How long to wait for the launch to produce a process before calling it a failed start. */
        private const val START_TIMEOUT_SECONDS = 30L

        /** Test events are delivered asynchronously and can trail the process exit slightly. */
        private const val TEST_EVENT_SETTLE_MILLIS = 500L
    }

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
            "exist; naming one that does not returns the list of the ones that do. Use " +
            "start_debug_configuration instead when you need to stop at a breakpoint."
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
                        "running in the IDE either way. Defaults to $DEFAULT_TIMEOUT_SECONDS, " +
                        "maximum $MAX_TIMEOUT_SECONDS.",
                )
            })
        })
        add("required", JsonArray().apply { add("name") })
    }

    override fun execute(input: JsonObject): String {
        val configurationName = input.get("name")?.asString?.trim().orEmpty()
        if (configurationName.isEmpty()) return "Error: missing 'name'"

        val timeoutSeconds = (input.get("timeout_seconds")?.asInt ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(configurationName)
            ?: return notFound(configurationName, runManager.allSettings)

        val executor = DefaultRunExecutor.getRunExecutorInstance()

        val output = StringBuilder()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val handlerRef = AtomicReference<ProcessHandler?>()
        val scope = Disposer.newDisposable("run_configuration")

        val collector = object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                synchronized(output) { output.append(event.text) }
            }

            override fun processTerminated(event: ProcessEvent) {
                exitCode.set(event.exitCode)
                finished.countDown()
            }
        }

        // Another configuration may be launched by the user while this one runs, so match on the
        // settings rather than taking whatever starts first. Identity first, because two
        // configurations are allowed to share a name across different types.
        fun matches(env: ExecutionEnvironment): Boolean =
            env.runnerAndConfigurationSettings?.let { it === settings }
                ?: (env.runProfile.name == settings.name)

        fun attach(env: ExecutionEnvironment, handler: ProcessHandler) {
            if (!matches(env)) return
            if (!handlerRef.compareAndSet(null, handler)) return

            handler.addProcessListener(collector)
            started.countDown()

            // Attaching from processStarted means startNotify has already run, so a process that
            // finished in the meantime will never call back. Its exit code is still readable.
            if (handler.isProcessTerminated) {
                exitCode.set(handler.exitCode ?: Int.MIN_VALUE)
                finished.countDown()
            }
        }

        val tests = TestResults()

        try {
            // Subscribed before the launch: a short-lived process can terminate before
            // executeConfiguration has even returned.
            val connection = project.messageBus.connect(scope)

            // A Gradle test configuration streams its results to the IDE as tooling-API events and
            // leaves the process handler's text stream empty, so the ProcessListener above sees
            // nothing at all -- only an exit code. This is the layer both Gradle and a plain JUnit
            // run report into, so it is what makes "which test failed, and why" answerable.
            connection.subscribe(
                SMTRunnerEventsListener.TEST_STATUS,
                object : SMTRunnerEventsAdapter() {
                    override fun onTestFailed(test: SMTestProxy) = tests.recordFailure(test)
                    override fun onTestFinished(test: SMTestProxy) = tests.recordFinished(test)
                },
            )

            connection.subscribe(
                ExecutionManager.EXECUTION_TOPIC,
                object : ExecutionListener {
                    /**
                     * The hook that matters for output: this fires before the handler's
                     * `startNotify`, so the listener is in place for the first byte. Attaching in
                     * [processStarted] instead loses everything a fast process printed before the
                     * Run window finished opening -- which for a short test run is all of it.
                     */
                    override fun processStarting(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                    ) = attach(env, handler)

                    /** Fallback for runners that never fire the three-argument `processStarting`. */
                    override fun processStarted(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                    ) = attach(env, handler)

                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                        if (!matches(env)) return
                        // Nothing will ever start, so do not sit out the full start timeout.
                        started.countDown()
                        finished.countDown()
                    }
                },
            )

            var failure: Exception? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    ProgramRunnerUtil.executeConfiguration(settings, executor)
                } catch (e: Exception) {
                    failure = e
                }
            }
            failure?.let { return "Error: could not start \"$configurationName\": ${it.message}" }

            val appeared = started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!appeared || handlerRef.get() == null) {
                // executeConfiguration reports its own failures in the Run window, not to the
                // caller, so a missing process is the only signal available here.
                return "\"$configurationName\" did not start. This is usually a compilation error " +
                    "or a broken configuration -- the IDE's Run tool window has the real error."
            }

            if (!finished.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                return "\"$configurationName\" is still running after ${timeoutSeconds}s. It is " +
                    "still going in the IDE's Run tool window. Progress so far:\n\n" +
                    report(tests, tail(output))
            }

            // The last few test events can land just after the process exits, so give them the
            // moment they need rather than reporting a truncated tally.
            if (tests.sawAnyTest) Thread.sleep(TEST_EVENT_SETTLE_MILLIS)
        } finally {
            Disposer.dispose(scope)
        }

        val code = exitCode.get()
        val verdict = when (code) {
            0 -> "finished successfully"
            Int.MIN_VALUE -> "finished, but the IDE reported no exit code"
            else -> "failed with exit code $code"
        }
        val body = report(tests, tail(output))
        return if (body.isBlank()) {
            "\"$configurationName\" $verdict, but produced neither test results nor console " +
                "output. If this is a Gradle configuration, its output goes to the Build tool " +
                "window; running the same thing with run_shell_command will show it."
        } else {
            "\"$configurationName\" $verdict.\n\n$body"
        }
    }

    /**
     * Test results when there were any, console text when there was any, both when both.
     *
     * Neither source is reliably present: a Gradle test run has results and no console text, an
     * application run has console text and no results.
     */
    private fun report(tests: TestResults, consoleText: String): String = buildString {
        if (tests.sawAnyTest) append("Tests: ").append(tests.summary())
        if (consoleText.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("Output:\n\n").append(consoleText)
        }
    }

    /**
     * Test outcomes gathered off the SMTRunner event stream.
     *
     * Counted here rather than read off the root proxy at the end because the root is only reliably
     * populated for runners that build a full tree, and every runner emits the per-test events.
     */
    private class TestResults {

        companion object {
            /** Enough failures to work with; the rest are counted but not spelled out. */
            private const val MAX_REPORTED_FAILURES = 20

            /** The top of a stack trace names the assertion; the rest is framework noise. */
            private const val STACK_TRACE_LINES = 12
        }

        private val lock = Any()
        private var passed = 0
        private var failed = 0
        private var ignored = 0
        private val failures = mutableListOf<String>()

        val sawAnyTest: Boolean get() = synchronized(lock) { passed + failed + ignored > 0 }

        fun recordFinished(test: SMTestProxy) {
            synchronized(lock) {
                // Failures arrive on onTestFailed as well; counting them there keeps the tally honest.
                when {
                    test.isIgnored -> ignored++
                    test.isPassed -> passed++
                    else -> Unit
                }
            }
        }

        fun recordFailure(test: SMTestProxy) {
            synchronized(lock) {
                failed++
                if (failures.size >= MAX_REPORTED_FAILURES) return
                failures += describe(test)
            }
        }

        private fun describe(test: SMTestProxy): String = buildString {
            append(test.name)
            test.errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("\n    ").append(it.replace("\n", "\n    "))
            }
            test.stacktrace?.trim()?.takeIf { it.isNotEmpty() }?.let { trace ->
                val head = trace.lineSequence().take(STACK_TRACE_LINES).joinToString("\n    ")
                append("\n    ").append(head)
            }
        }

        fun summary(): String = synchronized(lock) {
            val counts = buildList {
                add("$passed passed")
                if (failed > 0) add("$failed failed")
                if (ignored > 0) add("$ignored ignored")
            }.joinToString(", ")

            if (failures.isEmpty()) return@synchronized counts

            val omitted = failed - failures.size
            val more = if (omitted > 0) "\n\n... and $omitted more failure(s)." else ""
            "$counts\n\nFailures:\n\n" + failures.joinToString("\n\n") + more
        }
    }

    /** The tail rather than the head: a test run's failure summary is at the end. */
    private fun tail(output: StringBuilder): String {
        val text = synchronized(output) { output.toString() }
        if (text.length <= MAX_OUTPUT_CHARS) return text.trim()
        val kept = text.substring(text.length - MAX_OUTPUT_CHARS)
        return "[... ${text.length - MAX_OUTPUT_CHARS} earlier characters omitted ...]\n" + kept.trim()
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
