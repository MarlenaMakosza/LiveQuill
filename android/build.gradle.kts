plugins {
    id("com.android.application")
}

android {
    namespace = "dev.handnotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.handnotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
