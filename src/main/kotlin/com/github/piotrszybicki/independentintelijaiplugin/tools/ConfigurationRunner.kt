package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConfigurationRunner(private val project: Project) {

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 180
        const val MAX_TIMEOUT_SECONDS = 900

        const val DEFAULT_DEBUG_WAIT_SECONDS = 20
        const val MAX_DEBUG_WAIT_SECONDS = 120

        private const val MAX_OUTPUT_CHARS = 20_000

        private const val START_TIMEOUT_SECONDS = 30L

        private const val TEST_EVENT_SETTLE_MILLIS = 500L
    }

    fun run(settings: RunnerAndConfigurationSettings, label: String, timeoutSeconds: Int): String {
        val executor = DefaultRunExecutor.getRunExecutorInstance()

        val output = StringBuilder()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val handlerRef = AtomicReference<ProcessHandler?>()
        val scope = Disposer.newDisposable("configuration_runner")

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

        fun matches(env: ExecutionEnvironment): Boolean =
            env.runnerAndConfigurationSettings?.let { it === settings }
                ?: (env.runProfile.name == settings.name)

        fun attach(env: ExecutionEnvironment, handler: ProcessHandler) {
            if (!matches(env)) return
            if (!handlerRef.compareAndSet(null, handler)) return

            handler.addProcessListener(collector)
            started.countDown()

            if (handler.isProcessTerminated) {
                exitCode.set(handler.exitCode ?: Int.MIN_VALUE)
                finished.countDown()
            }
        }

        val tests = TestResults()

        try {
            val connection = project.messageBus.connect(scope)

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
                    override fun processStarting(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                    ) = attach(env, handler)

                    override fun processStarted(
                        executorId: String,
                        env: ExecutionEnvironment,
                        handler: ProcessHandler,
                    ) = attach(env, handler)

                    override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                        if (!matches(env)) return
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
            failure?.let { return "Error: could not start \"$label\": ${it.message}" }

            val appeared = started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!appeared || handlerRef.get() == null) {
                return "\"$label\" did not start. This is usually a compilation error " +
                    "or a broken configuration -- the IDE's Run tool window has the real error."
            }

            if (!finished.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                return "\"$label\" is still running after ${timeoutSeconds}s. It is " +
                    "still going in the IDE's Run tool window. Progress so far:\n\n" +
                    report(tests, tail(output))
            }

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
            "\"$label\" $verdict, but produced neither test results nor console " +
                "output. If this is a Gradle configuration, its output goes to the Build tool " +
                "window; running the same thing with run_shell_command will show it."
        } else {
            "\"$label\" $verdict.\n\n$body"
        }
    }

    fun debug(settings: RunnerAndConfigurationSettings, label: String, waitSeconds: Int): String {
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()

        if (ProgramRunner.getRunner(executor.id, settings.configuration) == null) {
            return "\"$label\" (${settings.type.displayName}) cannot be debugged: the IDE has no " +
                "debug runner for this kind of configuration, which is why its Debug button is " +
                "disabled. Run it instead, or debug something that can be -- a test or an " +
                "application configuration."
        }

        val started = AtomicReference<XDebugSession?>()
        val attached = CountDownLatch(1)
        val scope = Disposer.newDisposable("configuration_runner_debug")

        try {
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
                    ProgramRunnerUtil.executeConfiguration(settings, executor)
                } catch (e: Exception) {
                    failure = e
                }
            }
            failure?.let { return "Error: could not start \"$label\" under the debugger: ${it.message}" }

            if (!attached.await(waitSeconds.toLong(), TimeUnit.SECONDS)) {
                return "Started \"$label\" under the debugger, but no debug session appeared within " +
                    "${waitSeconds}s. That is usually a compilation error, or -- for a remote " +
                    "configuration -- nothing listening on the configured host and port, so check " +
                    "the target process is running with the matching -agentlib:jdwp options. The " +
                    "IDE's Debug tool window has the real error."
            }
        } finally {
            Disposer.dispose(scope)
        }

        val session = started.get()
        return "Debugger attached: \"$label\" is running as session " +
            "\"${session?.sessionName ?: label}\". Call await_breakpoint to wait for it to stop."
    }

    private fun report(tests: TestResults, consoleText: String): String = buildString {
        if (tests.sawAnyTest) append("Tests: ").append(tests.summary())
        if (consoleText.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("Output:\n\n").append(consoleText)
        }
    }

    private class TestResults {

        companion object {
            private const val MAX_REPORTED_FAILURES = 20

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

    private fun tail(output: StringBuilder): String {
        val text = synchronized(output) { output.toString() }
        if (text.length <= MAX_OUTPUT_CHARS) return text.trim()
        val kept = text.substring(text.length - MAX_OUTPUT_CHARS)
        return "[... ${text.length - MAX_OUTPUT_CHARS} earlier characters omitted ...]\n" + kept.trim()
    }
}
