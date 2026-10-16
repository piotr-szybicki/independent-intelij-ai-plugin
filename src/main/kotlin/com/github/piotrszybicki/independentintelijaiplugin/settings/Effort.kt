package com.github.piotrszybicki.independentintelijaiplugin.settings

enum class Effort(
    val wireValue: String?,
    val openAiValue: String?,
    val displayName: String,
) {
    PROVIDER_DEFAULT(null, null, "Provider default (field not sent)"),
    LOW("low", "low", "Low -- short, scoped work"),
    MEDIUM("medium", "medium", "Medium (recommended)"),
    HIGH("high", "high", "High -- the provider's own default"),
    XHIGH("xhigh", "high", "Extra high -- hard agentic work"),
    MAX("max", "high", "Maximum -- correctness over cost"),
    ;

    val fileName: String get() = wireValue ?: PROVIDER_DEFAULT_NAME

    companion object {
        fun parse(value: String): Effort? {
            val wanted =
                value
                    .trim()
                    .lowercase()
                    .replace('_', '-')
                    .replace(' ', '-')
            return entries.firstOrNull { effort ->
                wanted == effort.fileName || wanted == effort.name.lowercase().replace('_', '-')
            } ?: PROVIDER_DEFAULT.takeIf { wanted in PROVIDER_DEFAULT_ALIASES }
        }
    }
}

internal const val PROVIDER_DEFAULT_NAME = "provider-default"
internal val PROVIDER_DEFAULT_ALIASES = listOf(PROVIDER_DEFAULT_NAME, "default", "provider", "unset")
