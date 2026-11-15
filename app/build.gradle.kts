import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smoothcam"
    compileSdk = 36 // needed for Android 16's shutter-priority auto-exposure API

    defaultConfig {
        applicationId = "com.smoothcam"
        minSdk = 33
        targetSdk = 35
        // Version: set versionName by hand when releasing a new version.
        // versionCode comes from the cloud build number, so every APK installs over the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.5"
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No library dependencies: the app uses only the Android framework (Camera2 + MediaRecorder).
