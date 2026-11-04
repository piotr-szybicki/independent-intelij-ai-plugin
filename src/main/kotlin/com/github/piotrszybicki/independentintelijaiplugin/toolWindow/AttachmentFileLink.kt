package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.tools.ChatWorkingDirectory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A durable, clickable reference to a file attached to a chat message. */
internal object AttachmentFileLink {
    private const val SCHEME = "aicodingagent-file:"
    private val legacyAttachment = Regex("📎 `(.+) \\((whole file|line \\d+|lines \\d+-\\d+)\\)`")

    fun markdown(
        filePath: String?,
        label: String,
    ): String =
        if (filePath == null) {
            "📎 `$label`"
        } else {
            "📎 [${escapeLabel(label)}](${href(filePath)})"
        }

    /** Makes attachment markers saved before file links existed clickable as well. */
    fun linkLegacyMarkers(
        project: Project,
        markdown: String,
    ): String =
        legacyAttachment.replace(markdown) { match ->
            val path = match.groupValues[1]
            val label = File(path).name + " (${match.groupValues[2]})"
            this.markdown(resolvePath(project, path), label)
        }

    fun open(
        project: Project,
        target: String,
    ) {
        val path = if (target.startsWith(SCHEME)) decode(target) else target
        if (path == null) return
        val file =
            path
                .takeIf { it.contains("://") }
                ?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
                ?: LocalFileSystem.getInstance().findFileByPath(path)
        file?.let {
            FileEditorManager.getInstance(project).openFile(it, true)
        }
    }

    fun isFileLink(href: String): Boolean = href.startsWith(SCHEME)

    private fun href(path: String): String =
        SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(StandardCharsets.UTF_8))

    private fun decode(href: String): String? {
        val encodedPath = href.removePrefix(SCHEME)
        if (encodedPath.isEmpty()) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(encodedPath), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun resolvePath(
        project: Project,
        path: String,
    ): String {
        val file = File(path)
        return if (file.isAbsolute) {
            file.path
        } else {
            File(ChatWorkingDirectory.path(project) ?: project.basePath.orEmpty(), path).path
        }
    }

    private fun escapeLabel(label: String): String = label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
}
