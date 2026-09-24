pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ServiceTag"
include(":app", ":core")
// #62: the test-only foreign-UID sharer. Never a dependency of :app and never released.
include(":share-test-sender")

// ---- NEW (O15): the pinned shared library, as ordinary subprojects of THIS build ----
require(file("libs/nfc-tag-core/nfc-core/build.gradle.kts").isFile) {
    """
    libs/nfc-tag-core is missing or uninitialised.
    Clone with --recurse-submodules, or run:  git submodule update --init --recursive
    """.trimIndent()
}
include(":nfc-core", ":nfc-android")
project(":nfc-core").projectDir    = file("libs/nfc-tag-core/nfc-core")
project(":nfc-android").projectDir = file("libs/nfc-tag-core/nfc-android")
