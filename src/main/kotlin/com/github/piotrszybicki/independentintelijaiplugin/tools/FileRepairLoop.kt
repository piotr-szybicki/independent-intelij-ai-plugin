package com.github.piotrszybicki.independentintelijaiplugin.tools

/** Coordinates a private repair conversation without exposing its file contents to the caller. */
internal class FileRepairLoop(
    private val read: () -> String,
    private val analyse: () -> String,
    private val repair: (String, String) -> String,
    private val replace: (String, String) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    fun run(): String {
        var changed = false
        val visited = mutableSetOf<String>()
        while (true) {
            check(!isCancelled()) { "File repair was cancelled." }
            val original = read()
            val problems = analyse()
            check(!isCancelled()) { "File repair was cancelled." }
            check(!problems.startsWith("Error:")) { problems.removePrefix("Error: ") }
            check(read() == original) { "The file changed during analysis; retry analyse_and_fix." }
            if (problems.startsWith("No problems at severity ")) {
                return if (changed) "Problems were detected and fixed" else "No problems Found"
            }
            check(visited.add(original)) { "Repair made no progress or repeated an earlier version; problems remain." }
            val replacement = repair(original, problems).replace("\r\n", "\n").replace('\r', '\n')
            check(!isCancelled()) { "File repair was cancelled." }
            check(replacement.isNotBlank()) { "The repair agent returned an empty file; problems remain." }
            check(replacement != original) { "The repair agent returned the unchanged file; problems remain." }
            check(replacement !in visited) { "The repair agent repeated an earlier version; problems remain." }
            replace(original, replacement)
            changed = true
        }
    }
}
