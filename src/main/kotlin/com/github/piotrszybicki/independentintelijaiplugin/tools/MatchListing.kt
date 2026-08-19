package com.github.piotrszybicki.independentintelijaiplugin.tools

object MatchListing {

    data class Match(val path: String, val line: Int, val text: String? = null)

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

    fun count(matches: List<Match>): Int = matches.distinctBy { it.path to it.line }.size
}
