import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("dev.detekt") version "2.0.0-alpha.6"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
}

val pmdCpd =
    configurations.create("pmdCpd") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val pmdVersion = "7.28.0"
val cpdMinimumTokens = providers.gradleProperty("cpdMinimumTokens").orElse("100").map(String::toInt)
val cpdIncludeTests = providers.gradleProperty("cpdIncludeTests").orElse("false").map(String::toBoolean)
val cpdIgnoreIdentifiers = providers.gradleProperty("cpdIgnoreIdentifiers").orElse("false").map(String::toBoolean)
val cpdIgnoreLiterals = providers.gradleProperty("cpdIgnoreLiterals").orElse("false").map(String::toBoolean)
val cpdFailOnViolation = providers.gradleProperty("cpdFailOnViolation").orElse("true").map(String::toBoolean)

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

tasks.withType<RunIdeTask>().configureEach {
    // Avoid broken Windows registry performance counters in the development IDE's heartbeat metrics.
    systemProperty("oshi.os.windows.hkeyperfdata", "false")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

tasks.named("check") {
    // The source-set tasks add type resolution, which enables rules that the
    // aggregate detekt task cannot evaluate (for example NamedArguments).
    dependsOn("cpdCheck", "detektMain", "detektTest")
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    dependsOn(":detekt-rules:assemble")
}

tasks.register<JavaExec>("cpdCheck") {
    group = "verification"
    description = "Detects duplicated Kotlin code with PMD CPD."
    classpath = pmdCpd
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")

    val sourceDirectories =
        listOf(
            layout.projectDirectory.dir("src/main/kotlin"),
            layout.projectDirectory.dir("detekt-rules/src/main/kotlin"),
        ) +
            if (cpdIncludeTests.get()) {
                listOf(
                    layout.projectDirectory.dir("src/test/kotlin"),
                    layout.projectDirectory.dir("detekt-rules/src/test/kotlin"),
                )
            } else {
                emptyList()
            }

    args(
        "cpd",
        "--minimum-tokens",
        cpdMinimumTokens.get(),
        "--language",
        "kotlin",
        "--format",
        "text",
        "--relativize-paths-with",
        layout.projectDirectory.asFile,
    )
    sourceDirectories.forEach { args("--dir", it.asFile) }
    if (cpdIgnoreIdentifiers.get()) args("--ignore-identifiers")
    if (cpdIgnoreLiterals.get()) args("--ignore-literals")
    if (!cpdFailOnViolation.get()) args("--no-fail-on-violation")
}

tasks.register<JavaExec>("runHelloWorld") {
    group = "application"
    description = "Runs the Hello World application."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
}
dependencies {
    detektPlugins(project(":detekt-rules"))
    pmdCpd("net.sourceforge.pmd:pmd-cli:$pmdVersion")
    pmdCpd("net.sourceforge.pmd:pmd-kotlin:$pmdVersion")

    testImplementation("junit:junit:4.13.2")

    implementation("org.commonmark:commonmark:0.29.0")

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
