package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.google.gson.JsonObject

data class PsiTargetInput(
    val path: String,
    val line: Int,
    val symbol: String,
    val occurrence: Int,
) {
    companion object {
        fun from(input: JsonObject): PsiTargetInput {
            val path = input.get("path")?.asString ?: throw IllegalArgumentException("missing 'path'")
            val line = input.get("line")?.asInt ?: throw IllegalArgumentException("missing 'line'")
            val symbol = input.get("symbol")?.asString ?: throw IllegalArgumentException("missing 'symbol'")
            val occurrence = input.get("occurrence")?.asInt ?: 1
            require(occurrence >= 1) { "occurrence must be 1 or greater" }
            return PsiTargetInput(path, line, symbol, occurrence)
        }
    }
}
