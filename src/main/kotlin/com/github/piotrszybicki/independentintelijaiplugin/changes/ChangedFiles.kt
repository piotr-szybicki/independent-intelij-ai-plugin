package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.vfs.VirtualFile

interface ChangedFiles {
    fun changedFiles(): List<VirtualFile>

    fun changedPaths(): List<String>

    fun addListener(listener: ChangeSessionService.Listener)

    fun removeListener(listener: ChangeSessionService.Listener)
}
