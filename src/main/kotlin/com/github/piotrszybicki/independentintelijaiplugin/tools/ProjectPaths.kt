package com.github.piotrszybicki.independentintelijaiplugin.tools

import java.io.File

internal object ProjectPaths {
    fun resolve(
        baseDirectory: File,
        path: String,
    ): File? {
        val base = baseDirectory.canonicalFile
        val supplied = File(path)
        val target = (if (supplied.isAbsolute) supplied else File(base, path)).canonicalFile
        return target.takeIf { it.toPath().startsWith(base.toPath()) }
    }
}
