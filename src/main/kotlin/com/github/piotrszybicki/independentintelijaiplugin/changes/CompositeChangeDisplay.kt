package com.github.piotrszybicki.independentintelijaiplugin.changes

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

class CompositeChangeDisplay(private val displays: List<ChangeDisplay>) : ChangeDisplay, Disposable {

    init {
        displays.filterIsInstance<Disposable>().forEach { Disposer.register(this, it) }
    }

    override fun show(file: VirtualFile, baseline: String) = displays.forEach { it.show(file, baseline) }

    override fun clear(file: VirtualFile) = displays.forEach { it.clear(file) }

    override fun clearAll() = displays.forEach { it.clearAll() }

    override fun dispose() = Unit
}
