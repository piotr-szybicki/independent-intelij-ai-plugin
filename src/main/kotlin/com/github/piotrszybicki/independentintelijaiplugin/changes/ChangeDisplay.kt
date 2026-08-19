package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.vfs.VirtualFile

interface ChangeDisplay {

    fun show(file: VirtualFile, baseline: String)

    fun clear(file: VirtualFile)

    fun clearAll()
}
