package com.github.piotrszybicki.independentintelijaiplugin.tools

enum class ToolCategory(
    val displayName: String,
) {
    READ("Reading code"),
    NAVIGATE("Navigating code"),

    VCS("Version control"),

    EDIT("Editing and fixing"),
    RUN("Running code"),
    DEBUG("Debugging"),

    SUPPORT("Cutting output down"),
}
