pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin is published only to Google's Maven repo.
        google()
    }

    plugins {
        // Version chain, tightest constraint first — none of these is a
        // free choice:
        //   gdx-backend-android 1.14.2 -> androidx.core 1.17.0
        //   androidx.core 1.17.0       -> compileSdk 36, AGP >= 8.9.1
        //   AGP 8.13.x                 -> Gradle >= 8.13
        // So the wrapper is pinned at 8.13 and compileSdk at 36 in
        // android/build.gradle.kts. Changing any one of them means walking
        // the chain again. Declared here rather than in the root build
        // script so the root stays free of plugins it does not apply.
        id("com.android.application") version "8.13.2" apply false
    }
}

rootProject.name = "openfps"

// :engine is platform-free and must stay that way — it is the module CI can
// build and test on a machine with no display and no Android SDK.
include(":engine")

// :gdxshared is the libGDX code that is not platform-specific — the
// framebuffer presenter, the welcome screen, the UI state machine, the input
// accumulator. It depends on libGDX CORE and no backend, which is what lets
// both launchers use it and what keeps it buildable on a machine with neither
// a display nor an Android SDK. See gdxshared/build.gradle.kts.
include(":gdxshared")

include(":desktop")

// :tools is BUILD-TIME ONLY. It holds the glTF -> ModelFormat converter and
// depends on :engine one way, so it can compile against ModelFormat's layout
// constants. Nothing depends on :tools, which is exactly what keeps it — and
// the JSON library it uses — off every shipped runtime classpath. The root
// `verifyToolsIsolation` task fails the build if that ever stops being true.
// Included after :engine and :desktop so those are evaluated first.
include(":tools")

// :android needs an Android SDK, so it is only included when one is present.
// Without this guard, `gradlew :engine:build` on a machine with no SDK fails
// during settings evaluation — before it can reach the module that does not
// need one.
val androidSdkPresent = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

if (androidSdkPresent) {
    include(":android")
} else {
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties) " +
        "— skipping :android. The engine and desktop modules build normally."
    )
}
