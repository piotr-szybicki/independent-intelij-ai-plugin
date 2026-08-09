package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Waiting for the debugger to stop, and reading what is in scope once it has. Shared by the tools
 * that wait for a breakpoint and the ones that step, so both report a pause identically.
 *
 * The debugger's APIs are all callback-driven and answered on its own threads, while a tool has to
 * hand back one finished string. So each stage here is a latch: wait for the pause, ask the frame
 * for its children, then ask every child to present itself. That is also why the timeouts are per
 * stage -- a debuggee that never arrives and a value whose getter hangs look identical otherwise.
 *
 * Every method blocks and must run off the EDT: these calls are explicitly not for the UI thread,
 * and blocking it would freeze the IDE the user is debugging in.
 */
internal class DebuggerPause(private val project: Project) {

    companion object {
        /** Budget for the frame to list its children once we are paused. */
        private const val CHILDREN_TIMEOUT_MILLIS = 10_000L

        /** Budget for all the values to render themselves, in parallel. */
        private const val PRESENTATION_TIMEOUT_MILLIS = 10_000L

        private const val MAX_VARIABLES = 100
        private const val MAX_VALUE_CHARS = 300

        /**
         * Evaluated results get a longer leash than variables in scope: there are a hundred of the
         * latter and one of the former, and a big value is often exactly what was being asked for.
         */
        private const val MAX_EVALUATED_CHARS = 2_000
    }

    /**
     * Waits for a debug session to stop and returns it, or null on timeout.
     *
     * [trigger] runs once the listeners are in place -- stepping has to be issued from inside that
     * window, because a step can complete before the call that started it has returned.
     * [acceptAlreadyPaused] is for waiting on a breakpoint, where a session that is stopped right
     * now is the answer; stepping wants the *next* stop, not the current one.
     */
    fun awaitPause(
        timeoutMillis: Long,
        acceptAlreadyPaused: Boolean,
        trigger: () -> Unit = {},
    ): XDebugSession? {
        val manager = XDebuggerManager.getInstance(project)
        val paused = AtomicReference<XDebugSession?>()
        val stopped = CountDownLatch(1)
        val scope = Disposer.newDisposable("debugger-pause")

        try {
            fun watch(session: XDebugSession) {
                session.addSessionListener(
                    object : XDebugSessionListener {
                        override fun sessionPaused() {
                            // First pause wins; a later one is a separate call's business.
                            if (paused.compareAndSet(null, session)) stopped.countDown()
                        }
                    },
                    scope,
                )
            }

            manager.debugSessions.forEach(::watch)

            // A session started after this call still counts: the user may well launch the debugger
            // in response to being asked to.
            project.messageBus.connect(scope).subscribe(
                XDebuggerManager.TOPIC,
                object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) = watch(debugProcess.session)
                },
            )

            if (acceptAlreadyPaused) {
                manager.debugSessions.firstOrNull { it.isPaused && !it.isStopped }?.let { return it }
            }

            trigger()

            if (!stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null
            return paused.get()
        } finally {
            Disposer.dispose(scope)
        }
    }

    /** Where the session stopped, plus everything in scope there. */
    fun describe(session: XDebugSession): String {
        val position = session.currentPosition
        val where = if (position == null) {
            "an unknown location"
        } else {
            "${PsiTargets.relativePath(project, position.file)}:${position.line + 1}"
        }

        val header = "Stopped at $where in debug session \"${session.sessionName}\"."
        val frame = session.currentStackFrame
            ?: return "$header\nNo stack frame is available, so no variables could be read."

        val variables = try {
            readVariables(frame)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return "$header\nInterrupted while reading variables."
        }

        return buildString {
            append(header)
            append("\n\n")
            when {
                variables.error != null -> append("Could not read variables: ${variables.error}")
                variables.values.isEmpty() -> append("No variables are in scope here.")
                else -> {
                    append("Variables in scope:\n")
                    variables.values.forEach { append("  ${it}\n") }
                    if (variables.truncated) append("  ... (only the first $MAX_VARIABLES are shown)\n")
                }
            }
        }
    }

    // --- evaluation -----------------------------------------------------------------------------

    /**
     * Evaluates [expression] against the frame [session] is stopped in, and renders the result the
     * same way a variable in scope is rendered.
     *
     * The evaluator is the debugger's own, so the expression is whatever the debuggee's language
     * accepts -- and, as in the Evaluate Expression dialog, calling a method really calls it.
     */
    fun evaluate(session: XDebugSession, expression: String, timeoutMillis: Long): String {
        val frame = session.currentStackFrame
            ?: return "No stack frame is available at this stop, so there is nothing to evaluate against."
        val evaluator = frame.evaluator
            ?: return "This debugger cannot evaluate expressions at this stop."

        val result = AtomicReference<XValue?>()
        val failure = AtomicReference<String?>()
        val done = CountDownLatch(1)

        val callback = object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result1: XValue) {
                result.set(result1)
                done.countDown()
            }

            override fun errorOccurred(errorMessage: String) {
                failure.set(errorMessage)
                done.countDown()
            }

            /** Malformed rather than failed -- a syntax error, before anything ran. */
            override fun invalidExpression(error: String) {
                failure.set(error)
                done.countDown()
            }
        }

        try {
            // The frame's position is what gives the expression its scope: which locals are visible,
            // and what `this` means.
            evaluator.evaluate(expression, callback, frame.sourcePosition)
        } catch (e: Exception) {
            return "Could not evaluate \"$expression\": ${e.message ?: e::class.java.simpleName}"
        }

        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return "The debugger did not answer within ${timeoutMillis / 1000}s. The expression may " +
                "have called something that blocks, or the session may have resumed underneath it."
        }

        failure.get()?.let { return "Could not evaluate \"$expression\": $it" }
        val value = result.get() ?: return "The debugger returned neither a value nor an error."

        return present(listOf(expression to value), MAX_EVALUATED_CHARS).firstOrNull()
            ?: "$expression = <not rendered in time>"
    }

    // --- variables ------------------------------------------------------------------------------

    private class Variables(
        val values: List<String>,
        val error: String? = null,
        val truncated: Boolean = false,
    )

    private fun readVariables(frame: XStackFrame): Variables {
        val named = mutableListOf<Pair<String, XValue>>()
        val error = AtomicReference<String?>()
        val truncated = AtomicBoolean(false)
        val listed = CountDownLatch(1)

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                synchronized(named) {
                    for (i in 0 until children.size()) {
                        if (named.size >= MAX_VARIABLES) {
                            truncated.set(true)
                            break
                        }
                        named.add(children.getName(i) to children.getValue(i))
                    }
                }
                if (last) listed.countDown()
            }

            // Everything below ends the wait rather than reporting into a tree that does not exist.
            override fun tooManyChildren(remaining: Int) {
                truncated.set(true)
                listed.countDown()
            }

            override fun setAlreadySorted(alreadySorted: Boolean) = Unit

            override fun setErrorMessage(errorMessage: String) {
                error.set(errorMessage)
                listed.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) =
                setErrorMessage(errorMessage)

            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?,
            ) = Unit
        })

        if (!listed.await(CHILDREN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && named.isEmpty()) {
            return Variables(
                emptyList(),
                error = "the debugger did not answer within ${CHILDREN_TIMEOUT_MILLIS / 1000}s",
            )
        }
        error.get()?.let { return Variables(emptyList(), error = it) }

        val snapshot = synchronized(named) { named.toList() }
        return Variables(present(snapshot), truncated = truncated.get())
    }

    /** Every value renders itself asynchronously, so they are all asked at once and awaited together. */
    private fun present(
        values: List<Pair<String, XValue>>,
        maxValueChars: Int = MAX_VALUE_CHARS,
    ): List<String> {
        val rendered = ConcurrentHashMap<String, String>()
        val remaining = CountDownLatch(values.size)

        values.forEachIndexed { index, (name, value) ->
            val key = "$index:$name"
            val answered = AtomicBoolean(false)
            fun answer(text: String) {
                // Some values present twice -- a placeholder, then the real thing. The first
                // release of the latch is what matters; later text still overwrites the entry.
                rendered[key] = text
                if (answered.compareAndSet(false, true)) remaining.countDown()
            }

            value.computePresentation(
                object : XValueNode {
                    override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) =
                        answer(format(name, type, value, hasChildren, maxValueChars))

                    override fun setPresentation(
                        icon: Icon?,
                        presentation: XValuePresentation,
                        hasChildren: Boolean,
                    ) = answer(format(name, presentation.type, render(presentation), hasChildren, maxValueChars))

                    override fun setFullValueEvaluator(evaluator: XFullValueEvaluator) = Unit
                },
                XValuePlace.TREE,
            )
        }

        remaining.await(PRESENTATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        return values.mapIndexed { index, (name, _) ->
            rendered["$index:$name"] ?: "$name = <not evaluated in time>"
        }
    }

    private fun format(
        name: String,
        type: String?,
        value: String,
        hasChildren: Boolean,
        maxValueChars: Int,
    ): String {
        val trimmed = if (value.length <= maxValueChars) value else value.take(maxValueChars) + "…"
        return buildString {
            append(name)
            if (!type.isNullOrBlank()) append(": $type")
            append(" = ")
            append(trimmed)
            // Flagged rather than expanded: reading nested fields is another round of async calls.
            if (hasChildren) append("  (has fields)")
        }
    }

    /** Flattens a rich presentation to plain text; the styling is for a tree this tool does not have. */
    private fun render(presentation: XValuePresentation): String {
        val text = StringBuilder()
        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) {
                text.append(value)
            }

            override fun renderStringValue(value: String) {
                text.append('"').append(value).append('"')
            }

            override fun renderNumericValue(value: String) {
                text.append(value)
            }

            override fun renderKeywordValue(value: String) {
                text.append(value)
            }

            override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
                text.append(value)
            }

            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                text.append('"').append(value).append('"')
            }

            override fun renderComment(comment: String) {
                text.append(comment)
            }

            override fun renderSpecialSymbol(symbol: String) {
                text.append(symbol)
            }

            override fun renderError(error: String) {
                text.append("<error: ").append(error).append('>')
            }
        })
        return text.toString()
    }
}
