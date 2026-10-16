package com.github.piotrszybicki.independentintelijaiplugin.settings

enum class ThinkingMode(
    val displayName: String,
    val fileName: String,
    private val aliases: List<String>,
) {
    PROVIDER_DEFAULT("Provider default (field not sent)", PROVIDER_DEFAULT_NAME, emptyList()),
    ADAPTIVE("On -- the model decides how much (recommended)", "on", listOf("adaptive", "true", "yes", "enabled")),
    OFF("Off", "off", listOf("false", "no", "disabled", "none")),
    ;

    companion object {
        fun parse(value: String): ThinkingMode? {
            val wanted =
                value
                    .trim()
                    .lowercase()
                    .replace('_', '-')
                    .replace(' ', '-')
            return entries.firstOrNull { mode ->
                wanted == mode.fileName || wanted == mode.name.lowercase() || wanted in mode.aliases
            } ?: PROVIDER_DEFAULT.takeIf { wanted in PROVIDER_DEFAULT_ALIASES }
        }
    }
}
