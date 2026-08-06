package org.jetbrains.plugins.template.tools

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
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.jetbrains.plugins.template.anthropic.AnthropicTool

/**
 * Adds or removes a line breakpoint, so a debugging session can be set up from the chat instead of
 * the user being told which gutters to click.
 *
 * Breakpoints are the debugger's own state, not file content: they survive edits, are not part of
 * the change session, and Approve/Revert does not touch them.
 */
class ToggleBreakpointTool(private val project: Project) : AnthropicTool {

    override val name = "toggle_breakpoint"
    override val description =
        "Adds or removes a line breakpoint in a project file. Line numbers are 1-based and match " +
            "what read_project_file returns. Adding requires a line the debugger can actually stop " +
            "on -- an executable statement, not a blank line, comment, or declaration."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("path", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "File path relative to the project root, e.g. src/Main.kt")
            })
            add("line", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("description", "1-based line number to put the breakpoint on")
            })
            add("action", JsonObject().apply {
                addProperty("type", "string")
                add("enum", JsonArray().apply { add("add"); add("remove") })
                addProperty("description", "Whether to add a breakpoint at the line or remove the one there")
            })
        })
        add("required", JsonArray().apply { add("path"); add("line"); add("action") })
    }

    override fun execute(input: JsonObject): String {
        val path = input.get("path")?.asString ?: return "Error: missing 'path'"
        val line = input.get("line")?.asInt ?: return "Error: missing 'line'"
        val action = input.get("action")?.asString ?: return "Error: missing 'action'"
        if (action != "add" && action != "remove") {
            return "Error: 'action' must be \"add\" or \"remove\", not \"$action\""
        }
        if (line < 1) return "Error: line must be 1 or greater"

        val vf = PsiTargets.resolveProjectFile(project, path)
            ?: return "Error: file not found in the project: $path"
        if (vf.isDirectory) return "Error: $path is a directory"

        // The debugger counts lines from zero; every other tool here reports them from one.
        val line0 = line - 1
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val existing = existingAt(manager, vf, line0)

        return if (action == "add") add(manager, vf, path, line, line0, existing) else remove(manager, path, line, existing)
    }

    private fun add(
        manager: XBreakpointManager,
        vf: VirtualFile,
        path: String,
        line: Int,
        line0: Int,
        existing: List<XLineBreakpoint<*>>,
    ): String {
        existing.firstOrNull()?.let { return "A breakpoint is already set at $path:$line (${it.type.title})." }

        // Which kinds of breakpoint are legal here is a language question -- canPutAt is what the
        // gutter itself consults, so a line it rejects is one the debugger could not stop on.
        val type = try {
            ReadAction.compute<XLineBreakpointType<*>?, RuntimeException> {
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
                    added = addLineBreakpoint(manager, type, vf, line0)
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        failure?.let { return "Error: could not add the breakpoint at $path:$line: ${it.message}" }

        return "Added a ${added?.type?.title ?: type.title} breakpoint at $path:$line."
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

    /**
     * Matching on the file URL and line rather than asking per type: a line can hold breakpoints of
     * kinds this call has no reason to know about, and removal should take all of them.
     */
    private fun existingAt(manager: XBreakpointManager, vf: VirtualFile, line0: Int): List<XLineBreakpoint<*>> =
        ReadAction.compute<List<XLineBreakpoint<*>>, RuntimeException> {
            manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.fileUrl == vf.url && it.line == line0 }
        }

    /**
     * The properties object is per breakpoint type, so the type argument has to be reunited with its
     * own property type before the manager will take it.
     */
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
