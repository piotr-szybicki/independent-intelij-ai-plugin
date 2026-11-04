package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.build.BuildViewManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
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
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import java.util.concurrent.CompletableFuture

class RunActionTool(
    private val project: Project,
) : AICodingAgentTool {
    override val interruptible = false

    override val name = "run_action"
    override val description =
        "Runs an IDE action by id, after asking the user to approve it -- reformatting, " +
            "optimising imports, VCS operations. Discover ids with find_by_name type \"action\". " +
            "CompileProject waits for the rebuild result and returns available compiler errors. " +
            "InspectCode waits for the inspection and returns its findings after the user selects the scope. " +
            "Other actions may start background work; " +
            "their result only confirms they were invoked. Actions use the " +
            "current editor, so open the target file first. A dialog will wait for the user; " +
            "changes are undone with the IDE's Undo, not by reverting this chat's changes."
    override val inputSchema: JsonObject =
        JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "action_id",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "The action's id, e.g. \"ReformatCode\" or \"OptimizeImports\". " +
                                    "Find ids with find_by_name.",
                            )
                        },
                    )
                    add(
                        "reason",
                        JsonObject().apply {
                            addProperty("type", "string")
                            addProperty(
                                "description",
                                "A short explanation of why this action should run, shown to the user in the " +
                                    "approval prompt so they can judge it.",
                            )
                        },
                    )
                },
            )
            add("required", JsonArray().apply { add("action_id") })
        }

    override fun execute(input: JsonObject): String {
        val actionId =
            input
                .get("action_id")
                ?.asString
                ?.trim()
                .orEmpty()
        if (actionId.isEmpty()) return "Error: missing 'action_id'"

        val manager = ActionManager.getInstance()
        val action =
            manager.getAction(actionId)
                ?: return "Error: no action with id \"$actionId\". Use find_by_name with type \"action\" " +
                    "to look up the correct id."

        val label = action.templatePresentation.text?.takeIf { it.isNotBlank() } ?: actionId
        val reason = input.get("reason")?.asString?.takeIf { it.isNotBlank() }

        if (!confirm(actionId, label, reason)) {
            return "The user declined to run this action. Do not run it again; ask what to do instead."
        }

        val outcome = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeAndWait {
            runCatching { perform(actionId, action, outcome) }
                .onFailure { e ->
                    outcome.complete("Error: \"$label\" failed: ${e.message ?: e::class.java.simpleName}")
                }
        }
        return try {
            outcome.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "Interrupted while waiting for \"$label\"; the action may still be running."
        }
    }

    private fun perform(
        actionId: String,
        action: AnAction,
        outcome: CompletableFuture<String>,
    ) {
        val editor =
            FileEditorManager.getInstance(project).selectedTextEditor?.takeIf {
                FileDocumentManager.getInstance().getFile(it.document)?.let { file ->
                    ChatWorkingDirectory.contains(project, file.path)
                } == true
            }
        val editorFile =
            editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
                ?: PsiTargets.resolveProjectFile(project, ".")

        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.VIRTUAL_FILE, editorFile)
                .build()

        val event =
            AnActionEvent.createEvent(
                action,
                context,
                action.templatePresentation.clone(),
                ActionPlaces.UNKNOWN,
                ActionUiKind.NONE,
                null,
            )

        ActionUtil.updateAction(action, event)
        if (!event.presentation.isEnabled) {
            outcome.complete(
                "\"${event.presentation.text ?: actionId}\" is not available in the current " +
                    "context (it is disabled). Open or select the file it should apply to, then try again.",
            )
            return
        }

        if (actionId == "CompileProject") {
            rebuild(outcome)
            return
        }

        if (actionId == "InspectCode") {
            val inspection = TrackedInspectionAction(outcome)
            val inspectionEvent =
                AnActionEvent.createEvent(
                    inspection,
                    context,
                    action.templatePresentation.clone(),
                    ActionPlaces.UNKNOWN,
                    ActionUiKind.NONE,
                    null,
                )
            ActionUtil.performAction(inspection, inspectionEvent)
            if (!inspection.started && !outcome.isDone) {
                outcome.complete("Code inspection did not start.")
            }
            return
        }

        ActionUtil.performAction(action, event)
        outcome.complete(
            "Invoked \"${event.presentation.text ?: actionId}\" ($actionId); " +
                "any background work may still be running.",
        )
    }

    private fun rebuild(outcome: CompletableFuture<String>) {
        val sessionId = Any()
        val diagnostics = RebuildDiagnostics(sessionId)
        val listenerLifetime = Disposer.newDisposable("AI rebuild diagnostics")
        Disposer.register(project, listenerLifetime)
        outcome.whenComplete { _, _ -> Disposer.dispose(listenerLifetime) }
        project.getService(BuildViewManager::class.java).addListener(diagnostics::accept, listenerLifetime)

        val manager = ProjectTaskManager.getInstance(project)
        val task = manager.createAllModulesBuildTask(false, project)
        manager
            .run(ProjectTaskContext(sessionId), task)
            .onSuccess { result ->
                outcome.complete(
                    when {
                        result.isAborted -> "Rebuild Project was cancelled."
                        result.hasErrors() -> diagnostics.failureMessage()
                        else -> "Rebuild Project completed successfully."
                    },
                )
            }.onError { error ->
                outcome.complete(
                    "Error: Rebuild Project failed: ${error.message ?: error::class.java.simpleName}\n" +
                        diagnostics.failureMessage(),
                )
            }
    }

    private fun confirm(
        actionId: String,
        label: String,
        reason: String?,
    ): Boolean {
        var choice = -1
        ApplicationManager.getApplication().invokeAndWait {
            choice =
                Messages.showDialog(
                    project,
                    buildString {
                        append("The AI wants to run an IDE action:\n\n")
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
