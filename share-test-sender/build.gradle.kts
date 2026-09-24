plugins {
    alias(libs.plugins.android.application)
}

// Test infrastructure, never a product (#62). A second application with its own package, and so
// its own UID, that fires a real ACTION_SEND at ServiceTag's share target: ShareBoundaryTest uses
// it to prove what Android delivers from *another* app. `:app` does not depend on it and no
// release workflow names it; `:app:connectedDebugAndroidTest` installs it and CI's build job
// assembles it so it cannot rot. See README.md beside this file.
val senderId = "com.loosecannon.servicetag.testsender"

android {
    namespace = senderId
    compileSdk = 37

    defaultConfig {
        applicationId = senderId
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// Debug only: there is no release variant of the sender, so nothing can publish one by accident.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    // FileProvider, and nothing else.
    implementation(libs.androidx.core.ktx)
}
