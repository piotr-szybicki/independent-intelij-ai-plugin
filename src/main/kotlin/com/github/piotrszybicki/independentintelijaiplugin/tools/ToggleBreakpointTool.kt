package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode

class ToggleBreakpointTool(
    private val project: Project,
) : AICodingAgentTool {
    override val name = "toggle_breakpoint"
    override val description =
        "Adds or removes a line breakpoint in a project file. Line numbers are 1-based, as " +
            "read_project_file returns them. Adding requires an executable statement, not a blank " +
            "line, comment or declaration. On add, suspend=false creates a non-suspending log " +
            "breakpoint; condition, log_message and log_expression configure when and what it logs."
    override val inputSchema: JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "path",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
                        },
                    )
                    add(
                        "line",
                        JsonObject().apply {
                            addProperty("type", "integer")
                            addProperty("description", "1-based line number to put the breakpoint on")
                        },
                    )
                    add(
                        "action",
                        JsonObject().apply {
                            addProperty("type", "string")
                            add(
                                "enum",
                                JsonArray().apply {
                                    add("add")
                                    add("remove")
                                },
                            )
                            addProperty(
                                "description",
                                "Whether to add a breakpoint at the line or remove the one there",
                            )
                        },
                    )
                    add(
                        "suspend",
                        JsonObject().apply {
                            addProperty("type", "boolean")
                            addProperty(
                                "description",
                                "Add only: whether the breakpoint suspends execution. Defaults to true. " +
                                    "Set false for a log breakpoint that continues without stopping.",
                            )
                        },
                    )
                    add(
                        "condition",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "Add only: an expression in the debuggee's language. The breakpoint " +
                                    "triggers only when it evaluates to true.",
                            )
                        },
                    )
                    add(
                        "log_message",
                        JsonObject().apply {
                            addProperty("type", "boolean")
                            addProperty(
                                "description",
                                "Add only: when true, logs IntelliJ's standard breakpoint-hit message. " +
                                    "Defaults to false; use log_expression for custom text or values.",
                            )
                        },
                    )
                    add(
                        "log_expression",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "Add only: an expression in the debuggee's language whose value is " +
                                    "evaluated and written to the debugger console each time it triggers.",
                            )
                        },
                    )
                },
            )
            add(
                "required",
                JsonArray().apply {
                    add("path")
                    add("line")
                    add("action")
                },
            )
        }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val action = input.get("action")?.asString ?: return "Error: missing 'action'"
        if (action != "add" && action != "remove") {
            return "Error: 'action' must be \"add\" or \"remove\", not \"$action\""
        }
        if (line < 1) return "Error: line must be 1 or greater"

        val vf =
            PsiTargets.resolveProjectFile(project, path)
                ?: return "Error: file not found in the project: $path"
        if (vf.isDirectory) return "Error: $path is a directory"

        val line0 = line - 1
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val existing = existingAt(manager, vf, line0)
        val options =
            BreakpointOptions(
                suspend = input.get("suspend")?.asBoolean ?: true,
                condition = input.get("condition")?.asString?.takeIf { it.isNotBlank() },
                logMessage = input.get("log_message")?.asBoolean ?: false,
                logExpression = input.get("log_expression")?.asString?.takeIf { it.isNotBlank() },
            )

        return if (action ==
            "add"
        ) {
            add(manager, vf, path, line, line0, existing, options)
        } else {
            remove(manager, path, line, existing)
        }
    }

    private data class BreakpointOptions(
        val suspend: Boolean,
        val condition: String?,
        val logMessage: Boolean,
        val logExpression: String?,
    )

    private fun add(
        manager: XBreakpointManager,
        vf: VirtualFile,
        path: String,
        line: Int,
        line0: Int,
        existing: List<XLineBreakpoint<*>>,
        options: BreakpointOptions,
    ): String {
        existing.firstOrNull()?.let { return "A breakpoint is already set at $path:$line (${it.type.title})." }

        val type =
            try {
                ReadAction.computeBlocking<XLineBreakpointType<*>?, RuntimeException> {
                    XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(vf, line0, project) }
                }
            } catch (e: IndexNotReadyException) {
                return "Error: the IDE is still indexing; try again once it has finished"
            } ?: return "Error: no breakpoint can be set at $path:$line. Pick a line with an executable " +
                "statement -- blank lines, comments and declarations have no code to stop on."

        var added: XLineBreakpoint<*>? = null
        var failure: Exception? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> {
                try {
                    added = addLineBreakpoint(manager, type, vf, line0).also { configure(it, options) }
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        failure?.let { return "Error: could not add the breakpoint at $path:$line: ${it.message}" }

        return describeAdded(added, type, path, line, options)
    }

    private fun configure(
        breakpoint: XBreakpoint<*>,
        options: BreakpointOptions,
    ) {
        if (!options.suspend) breakpoint.setSuspendPolicy(SuspendPolicy.NONE)
        breakpoint.setLogMessage(options.logMessage)
        options.condition?.let { breakpoint.setConditionExpression(expression(it)) }
        options.logExpression?.let { breakpoint.setLogExpressionObject(expression(it)) }
    }

    private fun expression(text: String) =
        XDebuggerUtil.getInstance().createExpression(text, null, null, EvaluationMode.EXPRESSION)

    private fun describeAdded(
        added: XLineBreakpoint<*>?,
        type: XLineBreakpointType<*>,
        path: String,
        line: Int,
        options: BreakpointOptions,
    ): String =
        buildString {
            append("Added a ${added?.type?.title ?: type.title} breakpoint at $path:$line")
            val details =
                buildList {
                    add(if (options.suspend) "suspending" else "non-suspending")
                    options.condition?.let { add("condition: $it") }
                    if (options.logMessage) add("standard hit message enabled")
                    options.logExpression?.let { add("log expression: $it") }
                }
            append(" (${details.joinToString("; ")}).")
        }

    private fun remove(
        manager: XBreakpointManager,
        path: String,
        line: Int,
        existing: List<XLineBreakpoint<*>>,
    ): String {
        if (existing.isEmpty()) return "No breakpoint is set at $path:$line; nothing to remove."

        var failure: Exception? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> {
                try {
                    existing.forEach { manager.removeBreakpoint(it) }
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        failure?.let { return "Error: could not remove the breakpoint at $path:$line: ${it.message}" }

        val what = existing.joinToString(", ") { it.type.title }
        return "Removed ${existing.size} breakpoint(s) at $path:$line ($what)."
    }

    private fun existingAt(
        manager: XBreakpointManager,
        vf: VirtualFile,
        line0: Int,
    ): List<XLineBreakpoint<*>> =
        ReadAction.computeBlocking<List<XLineBreakpoint<*>>, RuntimeException> {
            manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.fileUrl == vf.url && it.line == line0 }
        }

    @Suppress("UNCHECKED_CAST")
    private fun addLineBreakpoint(
        manager: XBreakpointManager,
        type: XLineBreakpointType<*>,
        vf: VirtualFile,
        line0: Int,
    ): XLineBreakpoint<*> {
        val typed = type as XLineBreakpointType<XBreakpointProperties<*>>
        return manager.addLineBreakpoint(typed, vf.url, line0, typed.createBreakpointProperties(vf, line0))
    }
}
