package com.github.piotrszybicki.independentintelijaiplugin.tools

/**
 * Rendering a directory walk for the model.
 *
 * One full path per line is the obvious format and the wrong one: a recursive listing of a Java or
 * Kotlin source tree repeats `src/main/kotlin/com/github/<vendor>/<product>/` on every single line,
 * so most of the tokens the model pays for are a prefix it already read. The tree below prints each
 * directory once and hangs its file names underneath, which is the same information at a fraction
 * of the size -- on this plugin's own `src/main/kotlin`, roughly a fifth.
 *
 * Chains of directories that hold nothing but one subdirectory (the package prefix above) collapse
 * onto a single line, because indenting them one level at a time spends four lines saying nothing.
 */
object DirectoryListing {

    /** One entry from the walk, its path relative to the directory being listed. */
    data class Entry(val path: String, val isDirectory: Boolean)

    private class Dir(val name: String) {
        val dirs = LinkedHashMap<String, Dir>()
        val files = mutableListOf<String>()
    }

    /**
     * Formats [entries] as an indented tree under [shownPath], preserving the order they were
     * walked in. [truncated] says the walk stopped at the caller's entry cap rather than running out
     * of files, which the model needs to know before concluding a file is not there.
     */
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
