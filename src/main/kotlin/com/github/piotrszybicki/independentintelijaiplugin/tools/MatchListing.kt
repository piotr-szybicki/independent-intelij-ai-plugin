package com.github.piotrszybicki.independentintelijaiplugin.tools

/**
 * Rendering `path:line` search hits for the model.
 *
 * The flat form repeats the file's path on every hit, and a deep package puts eighty characters of
 * prefix in front of every twenty characters of code -- twenty hits in one file cost the model the
 * path twenty times. Grouping under one heading per file says the same thing once.
 *
 * The engine that produces the hits reports them from several threads, so they arrive interleaved
 * between files and out of order within one. Grouping therefore has to sort rather than rely on
 * equal paths already sitting together, and while sorting it drops hits that would render as the
 * same line twice -- two occurrences on one line are one line of output either way.
 */
object MatchListing {

    /** One hit: [text] is the source line, or null when the caller has only a location to show. */
    data class Match(val path: String, val line: Int, val text: String? = null)

    /**
     * Groups [matches] by file under [summary], which is the caller's own count-and-query line.
     * The returned count is of rendered lines, so it reflects the de-duplication above.
     */
    fun format(summary: String, matches: List<Match>, truncated: Boolean): String {
        val byFile = matches
            .distinctBy { it.path to it.line }
            .groupBy { it.path }
            .toSortedMap()

        return buildString {
            append(summary)
            if (truncated) {
                append(" (stopped at the limit; narrow the query, mask or directory to see the rest)")
            }
            append(":\n")
            for ((path, hits) in byFile) {
                append(path).append('\n')
                for (hit in hits.sortedBy { it.line }) {
                    append("  ").append(hit.line)
                    hit.text?.let { append(": ").append(it) }
                    append('\n')
                }
            }
        }
    }

    /** The rendered line count, which the caller needs for its summary before formatting. */
    fun count(matches: List<Match>): Int = matches.distinctBy { it.path to it.line }.size
}
