import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("org.commonmark:commonmark:0.29.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        // run_shell_command drives the IDE's own terminal so the user can watch and Ctrl+C.
        bundledPlugin("org.jetbrains.plugins.terminal")
        // get_file_problems uses CodeSmellDetector, which lives here because VCS is what runs
        // code analysis before a commit -- it is the platform's on-demand "analyse this file" entry point.
        bundledModule("intellij.platform.vcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
}
