package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.vfs.VirtualFile

/**
 * Renders "changed since the session baseline" markers for a file.
 *
 * This sits behind an interface because the only implementation leans on
 * [com.intellij.openapi.vcs.ex.SimpleLineStatusTracker], which lives in a semi-internal package and
 * could move between platform releases. Replacing it with hand-rolled markup highlighters should
 * not require touching the capture side.
 *
 * All methods are called on the EDT.
 */
interface ChangeDisplay {

    /** Shows [file] as modified relative to [baseline]; updates the baseline if already shown. */
    fun show(file: VirtualFile, baseline: String)

    /** Stops marking [file]. */
    fun clear(file: VirtualFile)

    /** Stops marking every file. */
    fun clearAll()
}
