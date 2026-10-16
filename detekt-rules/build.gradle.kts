plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("dev.detekt:detekt-api:2.0.0-alpha.6")

    testImplementation(kotlin("test-junit"))
    testImplementation("dev.detekt:detekt-test:2.0.0-alpha.6")
}
