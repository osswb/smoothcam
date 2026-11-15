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
        // Version = "<baseVersion>.<build number>", e.g. 0.5.7. Raise baseVersion by hand for a new
        // version; the build number comes from the cloud build, so every build is a newer version.
        // The release tag uses the same string (v0.5.7), which is what update checkers like Obtainium compare.
        val baseVersion = "0.5"
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = maxOf(buildNumber, 1)
        versionName = "$baseVersion.$buildNumber"
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
