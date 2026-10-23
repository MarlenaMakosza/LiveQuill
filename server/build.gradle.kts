import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val ktor = "3.6.0"

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("ch.qos.logback:logback-classic:1.6.5")
}

application {
    mainClass.set("io.github.marlenamakosza.livequill.server.MainKt")
    applicationName = "livequill-server"
}
