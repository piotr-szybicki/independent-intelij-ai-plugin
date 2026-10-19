package com.github.piotrszybicki.independentintelijaiplugin.files

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

object EditorBackedFileReader {
    fun read(
        path: Path,
        log: Logger,
        saveWarning: String,
        readWarning: String,
    ): String? {
        var text: String? = null
        WriteIntentReadAction.run {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            val documents = FileDocumentManager.getInstance()
            val document = file?.let { documents.getDocument(it) } ?: return@run
            runCatching { documents.saveDocument(document) }
                .onFailure { log.warn(saveWarning, it) }
            text = document.text
        }
        return text ?: runCatching { Files.readString(path) }
            .onFailure { log.warn(readWarning, it) }
            .getOrNull()
    }
}
