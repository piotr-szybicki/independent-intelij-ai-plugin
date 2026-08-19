package com.github.piotrszybicki.independentintelijaiplugin.tools

object DirectoryListing {

    data class Entry(val path: String, val isDirectory: Boolean)

    private class Dir(val name: String) {
        val dirs = LinkedHashMap<String, Dir>()
        val files = mutableListOf<String>()
    }

    fun format(shownPath: String, entries: List<Entry>, truncated: Boolean): String {
        if (entries.isEmpty()) return "$shownPath/ is empty."

        val root = Dir("")
        var directories = 0
        var files = 0
        for (entry in entries) {
            val segments = entry.path.split('/')
            var parent = root
            for (segment in segments.dropLast(1)) {
                parent = parent.dirs.getOrPut(segment) { Dir(segment) }
            }
            val name = segments.last()
            if (entry.isDirectory) {
                parent.dirs.getOrPut(name) { Dir(name) }
                directories++
            } else {
                parent.files.add(name)
                files++
            }
        }

        return buildString {
            append("$shownPath/: $directories director(y/ies), $files file(s)")
            if (truncated) {
                append(" (stopped at the limit; narrow the path or lower max_depth to see the rest)")
            }
            append("\n")
            appendDir(root, indent = 0)
        }
    }

    fun tree(paths: List<String>): String {
        val root = Dir("")
        for (path in paths.distinct()) {
            val segments = path.split('/', '\\').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var parent = root
            for (segment in segments.dropLast(1)) {
                parent = parent.dirs.getOrPut(segment) { Dir(segment) }
            }
            parent.files.add(segments.last())
        }
        return buildString { appendDir(root, indent = 0) }
    }

    private fun StringBuilder.appendDir(dir: Dir, indent: Int) {
        val pad = " ".repeat(indent)
        if (dir.files.isNotEmpty()) {
            append(pad).append(dir.files.joinToString(", ")).append('\n')
        }
        for (child in dir.dirs.values) {
            var node = child
            val label = StringBuilder(child.name)
            while (node.files.isEmpty() && node.dirs.size == 1) {
                node = node.dirs.values.first()
                label.append('/').append(node.name)
            }
            append(pad).append(label).append("/\n")
            appendDir(node, indent + 2)
        }
    }
}
