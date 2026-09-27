import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "com.slayf16"
version = "0.1.0"

repositories { mavenCentral() }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:3.5.1"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock")
}

application { mainClass.set("com.slayf16.mcppet.ApplicationKt") }
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Connects to an MCP endpoint, lists the tool, and invokes it."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.slayf16.mcppet.SmokeClientKt")
    standardInput = System.`in`
}


