package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConfigurationRunner(private val project: Project) {

    companion object {
        const val DEFAULT_DEBUG_WAIT_SECONDS = 20
        const val MAX_DEBUG_WAIT_SECONDS = 120
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
}
