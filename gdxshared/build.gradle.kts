// The libGDX code that is NOT platform-specific.
//
// Both shipped launchers use libGDX — LWJGL3 on desktop, the Android backend
// on a phone — and libGDX core itself is plain Java that runs on either. So
// there is a real middle layer between ":engine, which must never import
// libGDX" and ":desktop, which owns GLFW". This module is it.
//
// What belongs here is anything that needs libGDX and does NOT need to know
// which platform it is on:
//
//   FramebufferPresenter  uploads the software rasterizer's finished frame
//                         and draws it as a fullscreen quad. Pixmap, Texture
//                         and SpriteBatch are identical on both backends, and
//                         the three subtle properties its Javadoc records —
//                         de-padding, byte order, orientation — are the same
//                         three properties on both. Duplicating it per
//                         platform would be duplicating exactly the code most
//                         likely to drift and least likely to be noticed
//                         drifting.
//   MainMenuScreen +      the blocky welcome screen, drawn from tinted 1x1
//   Block*/Menu*          quads with no asset files at all. Density-aware, so
//                         one implementation serves a 1080p monitor and a
//                         560 dpi phone.
//   UiState(Machine)      whether the menu or the game is in front.
//   InputAccumulator      the accumulate-and-latch arithmetic that bridges a
//                         platform frame rate to a fixed simulation rate. It
//                         imports nothing from any toolkit and is the part CI
//                         can actually test.
//
// What does NOT belong here is anything naming a backend: Lwjgl3Application,
// AndroidApplication, GLFW, android.*. Those stay in :desktop and :android.
// A key code is platform-specific too — see hal/README.md on why :engine ships
// no default bindings and DesktopBindings / AndroidBindings do.
//
// This module needs no Android SDK, so `gradlew :gdxshared:build` works on any
// machine, exactly as :engine does.

plugins {
    id("java-library")
    id("checkstyle")
}

repositories {
    mavenCentral()
}

// Kept in step with :desktop and :android by hand. There is no version
// catalog yet; when there is, all three read from it.
val gdxVersion = "1.14.2"

dependencies {
    // api, not implementation: FramebufferPresenter takes a SoftwareRenderPort
    // and MenuActions is implemented by both launchers, so :engine's types are
    // part of this module's surface rather than an internal detail.
    api(project(":engine"))

    // libGDX CORE only. No backend, and that restriction is the whole point of
    // the module — adding gdx-backend-lwjgl3 here would put GLFW on Android's
    // classpath, and adding gdx-backend-android would put the Android SDK on
    // CI's. Either one makes this module unbuildable somewhere it currently
    // builds fine.
    api("com.badlogicgames.gdx:gdx:$gdxVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")

    // PixmapByteOrderTest calls GdxNativesLoader and builds a real Pixmap, so
    // it needs the desktop natives at TEST runtime — never at ship runtime,
    // which is what keeps them out of the APK. The test assumes-away rather
    // than fails if the load does not work, so a machine without them still
    // gets a green build.
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

checkstyle {
    toolVersion = "10.18.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Werror",
        "-Xlint:-options"
    ))
    options.isFork = true
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
    }
}
