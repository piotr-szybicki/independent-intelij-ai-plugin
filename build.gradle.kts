import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// Pin the compilation target to Java 21 -- the JVM used by IntelliJ Platform 2025.2.
// The toolchain fixes which JDK compiles the sources, independent of the JDK running Gradle.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21

        // The Kotlin compiler is pinned to 2.1.20 in settings.gradle.kts, but the language and API
        // levels would otherwise just follow it on every upgrade. Pinning them here decouples the two:
        // apiVersion caps the stdlib surface we may call, which matters because
        // kotlin.stdlib.default.dependency=false means we run against the IDE's bundled stdlib.
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
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
