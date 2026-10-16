package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.vfs.VirtualFile

interface ChangeReview {
    fun acceptHunk(
        file: VirtualFile,
        hunk: Hunk,
    )

    fun rejectHunk(
        file: VirtualFile,
        hunk: Hunk,
    )

    fun approveAll()

    fun revertAll(): List<String>
}
