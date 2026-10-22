package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentTool
import com.github.piotrszybicki.independentintelijaiplugin.aicodingagent.AICodingAgentUsage
import com.github.piotrszybicki.independentintelijaiplugin.history.ChatHistoryService
import com.github.piotrszybicki.independentintelijaiplugin.settings.AgentConfiguration
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

class AnalyseAndFixTool(
    private val project: Project,
) : AICodingAgentTool {
    override val name = "analyse_and_fix"
    override val description =
        "Runs fresh IDE error and warning analysis after an edit. If problems are found, a separate " +
            "repair chat receives the entire file and returns its complete replacement, repeating IDE analysis " +
            "and repair until clean. Waits for completion and returns only the final status."
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
                },
            )
            add("required", JsonArray().apply { add("path") })
        }

    private var configuration: AgentConfiguration? = null
    private var parentChatId = ""
    private var isCancelled: () -> Boolean = { false }
    private var onUsage: (AICodingAgentUsage) -> Unit = {}

    fun bind(
        configuration: AgentConfiguration,
        parentChatId: String,
        isCancelled: () -> Boolean,
        onUsage: (AICodingAgentUsage) -> Unit,
    ) {
        this.configuration = configuration
        this.parentChatId = parentChatId
        this.isCancelled = isCancelled
        this.onUsage = onUsage
    }

    override fun execute(input: JsonObject): String {
        val path =
            input.get("path")?.asString?.takeIf { it.isNotBlank() }
                ?: return "Error: missing 'path' argument"
        val vf =
            PsiTargets.resolveProjectFile(project, path)
                ?: return "Error: file not found or outside project: $path"
        if (vf.isDirectory) return "Error: not a file: $path"
        val configuration = configuration ?: return "Error: repair tool has no calling chat configuration"
        val conversation =
            lazy {
                FileRepairConversation(
                    ChatHistoryService.getInstance(project),
                    configuration,
                    parentChatId,
                    path,
                    onUsage,
                )
            }
        return try {
            FileRepairLoop(
                read = {
                    ReadAction.computeBlocking<String, RuntimeException> {
                        FileDocumentManager.getInstance().getDocument(vf)?.text
                            ?: error("Cannot open document for $path")
                    }
                },
                analyse = {
                    GetFileProblemsTool(project).execute(JsonObject().apply { addProperty("path", path) })
                },
                repair = { content, problems -> conversation.value.repair(content, problems) },
                replace = { original, replacement ->
                    ApplicationManager.getApplication().invokeAndWait {
                        WriteCommandAction.runWriteCommandAction(
                            project,
                            "Repair $path",
                            null,
                            Runnable {
                                check(!isCancelled()) { "File repair was cancelled." }
                                val document =
                                    FileDocumentManager.getInstance().getDocument(vf)
                                        ?: error("Cannot open document for $path")
                                check(document.isWritable) { "$path is read-only" }
                                check(
                                    document.text == original,
                                ) { "The file changed during repair; retry analyse_and_fix." }
                                document.setText(replacement)
                                PsiDocumentManager.getInstance(project).commitDocument(document)
                            },
                        )
                    }
                },
                isCancelled = isCancelled,
            ).run().also { if (conversation.isInitialized()) conversation.value.finish(it) }
        } catch (e: Exception) {
            val result = "Error: ${e.message ?: e::class.java.simpleName}"
            if (conversation.isInitialized()) conversation.value.finish(result)
            result
        }
    }
}
