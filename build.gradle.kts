import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("dev.detekt") version "2.0.0-alpha.6"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
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

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

tasks.named("check") {
    // The source-set tasks add type resolution, which enables rules that the
    // aggregate detekt task cannot evaluate (for example NamedArguments).
    dependsOn("detektMain", "detektTest")
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    dependsOn(":detekt-rules:assemble")
}

tasks.register<JavaExec>("runHelloWorld") {
    group = "application"
    description = "Runs the Hello World application."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
}
dependencies {
    detektPlugins(project(":detekt-rules"))

    testImplementation("junit:junit:4.13.2")

    implementation("org.commonmark:commonmark:0.30.0")

    implementation("com.knuddels:jtokkit:1.1.0")

    implementation("com.mysql:mysql-connector-j:26.7.0") {
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
