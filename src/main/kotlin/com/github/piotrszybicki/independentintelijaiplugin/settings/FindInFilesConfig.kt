package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What [com.github.piotrszybicki.independentintelijaiplugin.tools.FindInFilesTool] refuses to search
 * for, as the `find-in-files` section of [AgentConfiguration.FILE_NAME] says it.
 *
 * ```json
 * "find-in-files": {
 *   "blocked-phrases": ["public", "fun", "import"]
 * }
 * ```
 *
 * A project has a handful of words that appear in every file it has, and a model that searches for
 * one of them gets back a listing the length of the project and learns nothing from it -- while the
 * whole thing stays in the conversation and is re-sent on every turn afterwards. The cap on files
 * bounds how bad that is; this stops it happening at all, because the useful answer to "where is
 * `public`" is not a shorter list, it is "ask a better question".
 *
 * Which words those are is a property of the project rather than of the plugin, so the list lives in
 * the file next to the providers and starts empty -- nothing is blocked until somebody says so.
 *
 * A phrase blocks a search when it is the whole query, ignoring case and surrounding space. Not a
 * substring: blocking `get` would otherwise take `getUserConfiguration` with it, and a longer query
 * containing a blocked word is a narrower search, which is the thing being asked for.
 */
data class FindInFilesConfig(
    /** Queries that are refused outright, as written in the file. Empty blocks nothing. */
    val blockedPhrases: List<String>,
) {

    /**
     * The phrase that refuses [query], or null when the search may run.
     *
     * Returns the phrase rather than a boolean so the tool can name it: a model told only "no" tries
     * a case variant of the same word next, while one told which phrase is blocked moves on.
     */
    fun blocking(query: String): String? {
        val normalised = query.trim()
        if (normalised.isEmpty()) return null
        return blockedPhrases.firstOrNull { it.equals(normalised, ignoreCase = true) }
    }

    fun toJson(): JsonObject = JsonObject().apply {
        add(BLOCKED_PHRASES, JsonArray().apply { blockedPhrases.forEach { add(it) } })
    }

    companion object {

        /** The top-level field of [AgentConfiguration.FILE_NAME] this is read from. */
        const val SECTION = "find-in-files"

        private const val BLOCKED_PHRASES = "blocked-phrases"

        /**
         * What a file with no section means, and what the starter file is written with: nothing
         * blocked. Which words are worthless to search for is the project's business, and guessing
         * at them would refuse a search the user never asked to have refused.
         */
        val DEFAULT = FindInFilesConfig(blockedPhrases = emptyList())

        /**
         * The section of [text], or [DEFAULT] when there is none.
         *
         * Absent is a real answer -- the file predates this section. A section that is there and
         * malformed is reported rather than ignored, on the same reasoning as everything else in
         * this file: a list silently read as empty is a block that quietly stopped blocking.
         */
        fun parse(text: String): FindInFilesConfig {
            if (text.isBlank()) return DEFAULT

            val root = try {
                JsonParser.parseString(text)
            } catch (e: Exception) {
                throw AgentConfigurationException("not valid JSON: ${e.message}")
            }
            // A bare array at the top level is the configurations and nothing else, which parseAll
            // accepts and which has nowhere to put this section.
            if (root == null || root.isJsonNull || !root.isJsonObject) return DEFAULT

            val section = root.asJsonObject.get(SECTION) ?: return DEFAULT
            if (!section.isJsonObject) {
                throw AgentConfigurationException("\"$SECTION\" must be an object")
            }

            val element = section.asJsonObject.get(BLOCKED_PHRASES) ?: return DEFAULT
            if (!element.isJsonArray) {
                throw AgentConfigurationException(
                    "\"$SECTION\".$BLOCKED_PHRASES must be an array of phrases in quotes",
                )
            }
            val phrases = element.asJsonArray.map {
                if (!it.isJsonPrimitive) {
                    throw AgentConfigurationException("\"$SECTION\".$BLOCKED_PHRASES must hold phrases in quotes")
                }
                it.asString.trim()
            }
            // Blanks would block nothing -- blocking() never asks about an empty query -- and a
            // duplicate is a line the reader has to check twice for no reason.
            return FindInFilesConfig(phrases.filter { it.isNotBlank() }.distinct())
        }
    }
}
