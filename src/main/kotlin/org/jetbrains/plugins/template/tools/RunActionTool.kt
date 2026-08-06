package org.jetbrains.plugins.template.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.template.anthropic.AnthropicTool

/**
 * Asks the user to let an IDE action run, then runs it -- reformat, optimise imports, run
 * configurations, VCS operations, anything bound to a menu item or shortcut.
 *
 * The gate is the user rather than a validator, for the same reason run_shell_command's is: an
 * action id can name almost anything the IDE can do, including things that touch files outside the
 * project or cannot be undone, so there is no useful set of "safe" ids to allow automatically.
 *
 * What the action does is also outside the change session -- it edits through the IDE's own
 * machinery rather than this plugin's, so Approve/Revert has no baseline for it. The IDE's own
 * Undo does.
 */
class RunActionTool(private val project: Project) : AnthropicTool {

    /**
     * An action that opens a dialog owns the UI thread until the user answers it, and interrupting
     * this thread would not close the dialog -- it would only stop us waiting for it.
     */
    override val interruptible = false

    override val name = "run_action"
    override val description =
        "Runs an IDE action by its id, after asking the user to approve it. Use find_by_name with " +
            "type \"action\" to discover ids. Good for things the IDE does better than editing " +
            "text by hand -- reformatting, optimising imports, running a configuration, VCS " +
            "operations. The action runs against the current editor and project, so open or select " +
            "the file it should apply to first. An action that opens a dialog will wait for the " +
            "user to answer it. Changes an action makes are undone with the IDE's Undo, not by " +
            "reverting this chat's changes."
    override val inputSchema: JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add("action_id", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "The action's id, e.g. \"ReformatCode\" or \"OptimizeImports\". Find ids with find_by_name.",
                )
            })
            add("reason", JsonObject().apply {
                addProperty("type", "string")
                addProperty(
                    "description",
                    "A short explanation of why this action should run, shown to the user in the " +
                        "approval prompt so they can judge it.",
                )
            })
        })
        add("required", JsonArray().apply { add("action_id") })
    }

    override fun execute(input: JsonObject): String {
        val actionId = input.get("action_id")?.asString?.trim().orEmpty()
        if (actionId.isEmpty()) return "Error: missing 'action_id'"

        val manager = ActionManager.getInstance()
        val action = manager.getAction(actionId)
            ?: return "Error: no action with id \"$actionId\". Use find_by_name with type \"action\" " +
                "to look up the correct id."

        val label = action.templatePresentation.text?.takeIf { it.isNotBlank() } ?: actionId
        val reason = input.get("reason")?.asString?.takeIf { it.isNotBlank() }

        if (!confirm(actionId, label, reason)) {
            // The user declining is an ordinary outcome, not a failure: report it as a result so the
            // model adapts instead of retrying the same action.
            return "The user declined to run this action. Do not run it again; ask what to do instead."
        }

        var outcome = "Error: the action did not run"
        ApplicationManager.getApplication().invokeAndWait {
            outcome = runCatching { perform(actionId, action) }
                .getOrElse { e -> "Error: \"$label\" failed: ${e.message ?: e::class.java.simpleName}" }
        }
        return outcome
    }

    private fun perform(actionId: String, action: AnAction): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val editorFile = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }

        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, editorFile)
            .build()

        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)

        // Ask the action whether it applies before firing it: an action that is disabled in this
        // context would otherwise silently do nothing and read as success.
        ActionUtil.performDumbAwareUpdate(action, event, true)
        if (!event.presentation.isEnabled) {
            return "\"${event.presentation.text ?: actionId}\" is not available in the current " +
                "context (it is disabled). Open or select the file it should apply to, then try again."
        }

        ActionUtil.performActionDumbAwareWithCallbacks(action, event)
        return "Ran \"${event.presentation.text ?: actionId}\" ($actionId)."
    }

    /** Blocks the agent thread on the EDT dialog: nothing may run until the user has decided. */
    private fun confirm(actionId: String, label: String, reason: String?): Boolean {
        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice = Messages.showDialog(
                project,
                buildString {
                    append("Claude wants to run an IDE action:\n\n")
                    append("$label  ($actionId)\n\n")
                    reason?.let { append("Why: $it\n\n") }
                    append(
                        "It runs against the current project and editor, with your permissions. " +
                            "Use the IDE's Undo if you want to take it back.",
                    )
                },
                "Run IDE Action?",
                arrayOf("Run", "Don't Run"),
                1,
                Messages.getWarningIcon(),
            )
        }
        return choice == 0
    }
}
