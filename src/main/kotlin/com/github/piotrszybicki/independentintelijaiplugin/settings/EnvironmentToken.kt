package com.github.piotrszybicki.independentintelijaiplugin.settings

object EnvironmentToken {
    fun variableName(token: String): String? {
        val trimmed = token.trim()
        if (!trimmed.startsWith("$")) return null
        val body =
            trimmed.drop(1).let {
                if (it.startsWith("{") && it.endsWith("}")) {
                    it.drop(1).dropLast(1)
                } else {
                    it
                }
            }
        return body.trim().takeIf { it.isNotBlank() }
    }
}
