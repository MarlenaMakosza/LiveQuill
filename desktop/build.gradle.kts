import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
}

compose.desktop {
    application {
        mainClass = "io.github.marlenamakosza.livequill.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "LiveQuill"
            packageVersion = providers.gradleProperty("appVersion").get()
        }
    }
}
