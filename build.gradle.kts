import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25

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

    implementation("com.knuddels:jtokkit:1.1.0")

    implementation("com.mysql:mysql-connector-j:9.7.0") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    intellijPlatform {
        intellijIdea("2026.2.0.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("intellij.testRunner.plugin")
        bundledPlugin("Git4Idea")
        bundledLibrary("lib/intellij.platform.vcs.impl.jar")
        bundledLibrary("lib/intellij.platform.vcs.jar")
        bundledLibrary("lib/intellij.platform.vcs.dvcs.jar")
        bundledLibrary("lib/intellij.platform.vcs.dvcs.impl.jar")
        testFramework(TestFrameworkType.Platform)
    }
}
