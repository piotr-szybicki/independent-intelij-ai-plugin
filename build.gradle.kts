import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

// Pin the compilation target to Java 25 -- the JVM that IntelliJ Platform 2026.2 runs on.
// The toolchain fixes which JDK compiles the sources, independent of the JDK running Gradle.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25

        // The Kotlin compiler is pinned to 2.4.0 in settings.gradle.kts, but the language and API
        // levels would otherwise just follow it on every upgrade. Pinning them here decouples the two:
        // apiVersion caps the stdlib surface we may call, which matters because
        // kotlin.stdlib.default.dependency=false means we run against the IDE's bundled stdlib --
        // 2.4.0 in IntelliJ 2026.2.
        languageVersion = KotlinVersion.KOTLIN_2_4
        apiVersion = KotlinVersion.KOTLIN_2_4
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("org.commonmark:commonmark:0.29.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.0.1")
        // run_shell_command drives the IDE's own terminal so the user can watch and Ctrl+C.
        bundledPlugin("org.jetbrains.plugins.terminal")
        // ConfigurationRunner listens to test events via SMTestProxy / SMTRunnerEventsListener.
        // These were core platform classes through 2025.x; 2026.2 moved them out into the bundled
        // Test Runner plugin (plugins/platform-testRunner-plugin), so they now need declaring.
        bundledPlugin("intellij.testRunner.plugin")
        // The git_* tools run git through git4idea rather than a process of their own.
        bundledPlugin("Git4Idea")
        // get_file_problems uses CodeSmellDetector (com.intellij.openapi.vcs), the platform's
        // on-demand "analyse this file" entry point -- it lives in VCS because that is what runs
        // code analysis before a commit.
        //
        // Up to 2025.x this was bundledModule("intellij.platform.vcs.impl"), but 2026.2 flattened
        // lib/modules/ into lib/ and the Gradle plugin (2.18.1) cannot resolve modules there yet --
        // see https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2165.
        // bundledLibrary() is the documented workaround for exactly that gap; switch back to
        // bundledModule() once the Gradle plugin catches up with the 262 classpath layout.
        bundledLibrary("lib/intellij.platform.vcs.impl.jar")
        // git_status reads ChangeListManager (intellij.platform.vcs) and GitRepository inherits its
        // root, branch and state from com.intellij.dvcs.repo.Repository (intellij.platform.vcs.dvcs),
        // while GitRepositoryManager extends AbstractRepositoryManager (intellij.platform.vcs.dvcs.impl)
        // -- a supertype the compiler has to see even though we only call through GitRepositoryManager.
        // Same flattened-module problem as above: all three are modules that 2026.2 moved into lib/.
        bundledLibrary("lib/intellij.platform.vcs.jar")
        bundledLibrary("lib/intellij.platform.vcs.dvcs.jar")
        bundledLibrary("lib/intellij.platform.vcs.dvcs.impl.jar")
        testFramework(TestFrameworkType.Platform)
    }
}
