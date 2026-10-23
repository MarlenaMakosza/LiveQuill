plugins {
    id("com.android.application")
}

val appVersion = providers.gradleProperty("appVersion").get()

/** 1.2.3 -> 10203. Android needs a growing integer per release. */
fun versionCodeOf(v: String): Int {
    val (major, minor, patch) = (v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
    return major * 10000 + minor * 100 + patch
}

// Release signing comes from environment variables (CI secrets, or ~/.config/livequill-signing/signing.env locally).
// Without them the release APK stays unsigned.
val keystorePath: String? = System.getenv("LIVEQUILL_KEYSTORE")

android {
    namespace = "io.github.marlenamakosza.livequill"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.marlenamakosza.livequill"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LIVEQUILL_STORE_PASSWORD")
                keyAlias = System.getenv("LIVEQUILL_KEY_ALIAS")
                keyPassword = System.getenv("LIVEQUILL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.work:work-runtime:2.12.0")
}
