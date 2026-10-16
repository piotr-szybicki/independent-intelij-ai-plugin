package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

internal class PromptHistory {
    private val prompts = mutableListOf<String>()
    private var position = 0
    private var draft = ""

    fun record(prompt: String) {
        val submitted = prompt.trim()
        if (submitted.isNotEmpty()) prompts += submitted
        resetNavigation()
    }

    fun previous(currentText: String): String? {
        if (prompts.isEmpty()) return null
        if (position == prompts.size) draft = currentText
        if (position > 0) position--
        return prompts[position]
    }

    fun next(): String? {
        if (position >= prompts.size) return null
        position++
        return if (position == prompts.size) draft else prompts[position]
    }

    fun restore(saved: List<String>?) {
        prompts.clear()
        prompts += saved.orEmpty().filter { it.isNotBlank() }
        resetNavigation()
    }

    val snapshot: List<String>
        get() = prompts.toList()

    private fun resetNavigation() {
        position = prompts.size
        draft = ""
    }
}
