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

    // Permanent signing key, provided by the cloud build from repository secrets
    // (SIGNING_KEYSTORE_BASE64 / SIGNING_PASSWORD). Android only installs an update when it is
    // signed with the same key as the installed app; without the secrets each build would get a
    // throwaway key and could not update the app.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("stable") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "smoothcam"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("stable")
        }
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
