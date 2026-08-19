package com.github.piotrszybicki.independentintelijaiplugin.agents

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AgentFiles {

    private const val DIRECTORY = ".cache"

    private val LOG = Logger.getInstance(AgentFiles::class.java)

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val HEADER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun spec(project: Project, agent: AgentDefinition, markdown: String): Path? {
        val now = LocalDateTime.now()
        return save(project, agent.name, "spec", now) {
            header(
                "Specification for @${agent.name}, drafted ${HEADER_STAMP.format(now)}.",
                "All of this file is what the agent is started with.",
            ) + specBody(agent, markdown)
        }
    }

    fun summary(project: Project, agentName: String, markdown: String): Path? {
        val now = LocalDateTime.now()
        return save(project, agentName, "summary", now) {
            header(
                "Summary from @$agentName, returned ${HEADER_STAMP.format(now)}.",
                "All of this file goes back to the chat that started the agent.",
            ) + markdown.trim().ifEmpty { "The agent's reply was empty; write what should go back." }
        }
    }

    fun open(project: Project, path: Path): Boolean {
        val file = findFile(path)
        if (file == null) {
            LOG.warn("Wrote $path but could not find it in the VFS")
            return false
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        return true
    }

    fun read(path: Path): String? {
        var text: String? = null
        WriteIntentReadAction.run {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            val documents = FileDocumentManager.getInstance()
            val document = file?.let { documents.getDocument(it) } ?: return@run
            runCatching { documents.saveDocument(document) }
                .onFailure { LOG.warn("Could not save $path before sending it; using the editor's copy", it) }
            text = document.text
        }
        return text ?: runCatching { Files.readString(path) }
            .onFailure { LOG.warn("Could not read $path", it) }
            .getOrNull()
    }

    fun displayPath(project: Project, path: Path): String {
        val root = project.basePath ?: return path.toString()
        val relative = runCatching { Path.of(root).relativize(path) }.getOrNull() ?: return path.toString()
        return relative.toString().replace('\\', '/')
    }

    private fun specBody(agent: AgentDefinition, markdown: String): String =
        markdown.trim().takeIf { it.isNotEmpty() }
            ?: agent.specTemplate.takeIf { it.isNotEmpty() }
            ?: "Describe what @${agent.name} should do."

    private fun save(
        project: Project,
        agentName: String,
        kind: String,
        now: LocalDateTime,
        text: () -> String,
    ): Path? {
        val root = project.basePath ?: return null
        val path = Path.of(root, DIRECTORY, "${sanitize(agentName)}-$kind-${FILE_STAMP.format(now)}.md")

        return try {
            Files.createDirectories(path.parent)
            Files.writeString(path, text() + "\n")
            WriteIntentReadAction.run { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }
            path
        } catch (e: Exception) {
            LOG.warn("Could not write the $kind for @$agentName to $path", e)
            null
        }
    }

    private fun header(what: String, how: String): String = "<!-- $what\n     $how -->\n\n"

    private fun findFile(path: Path): VirtualFile? {
        var file: VirtualFile? = null
        WriteIntentReadAction.run {
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        }
        return file
    }

    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "agent" }.take(60)
}
