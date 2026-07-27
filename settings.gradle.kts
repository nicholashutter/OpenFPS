pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin is published only to Google's Maven repo.
        google()
    }
}

rootProject.name = "openfps"

// :engine is platform-free and must stay that way — it is the module CI can
// build and test on a machine with no display and no Android SDK.
include(":engine")
include(":desktop")
