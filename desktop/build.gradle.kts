// Desktop launcher. The only module allowed to touch LWJGL3/GLFW.
//
// libGDX is used strictly as a HAL backend: window, GL context, input, and
// Scene2D for menu UI. Its Application/ApplicationListener lifecycle drives
// the platform pump, but the engine's own GameLoop remains the simulation
// clock — libGDX does not own the tick rate. See hal/README.md.

plugins {
    id("java")
    id("application")
    id("checkstyle")
}

repositories {
    mavenCentral()
}

// 1.14.2 is the current release on Maven Central. Verified against
// repo1.maven.org/maven2/com/badlogicgames/gdx/gdx/maven-metadata.xml —
// 1.14.3 is referenced in places online but was never published.
val gdxVersion = "1.14.2"

dependencies {
    implementation(project(":engine"))

    // libGDX core + the LWJGL3 desktop backend. gdx-platform carries the
    // precompiled natives; without the natives-desktop classifier the backend
    // links but fails at runtime with UnsatisfiedLinkError.
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.openfps.desktop.DesktopLauncher")
}

// Forward the opt-in diagnostics through to the forked JVM.
//
// `run` is a JavaExec, so a -D on the Gradle command line lands on the DAEMON
// and never reaches the application. That is why the capture properties looked
// inert when passed the obvious way. Forwarding them explicitly is what makes
// the windowed path verifiable at all:
//
//   gradlew :desktop:run -Dopenfps.screenshot=C:\tmp\window.png `
//                        -Dopenfps.screenshotFrame=90 -Dopenfps.screenshotExit=true
//
//   gradlew :desktop:run -Dopenfps.fpsLog=2
//     FramebufferPresenter logs platform, presented and rendered frame rates
//     every two seconds. It is the ONLY way to measure the windowed frame rate:
//     a headless tool measures how long a frame takes, not how many reach a
//     display, and those stopped being the same number the moment coalescing
//     and the presentation handoff entered the picture.
//
// Absent, both stay off and `run` behaves exactly as before — GdxScreenshot
// disables itself when no path is set, and the frame-rate log when no interval
// is set.
tasks.named<JavaExec>("run") {
    // Run from the REPOSITORY ROOT, not from desktop/.
    //
    // JavaExec defaults its working directory to the subproject, so
    // DesktopLauncher's default --assets=assets/models resolved to
    // desktop/assets/models — a directory that has never existed and never
    // will. The demo failed to find its own models with a perfectly worded
    // error about the wrong path. assets/ is a root-level directory that
    // :tools:regenerateDemoAssets writes to, so the root is the correct
    // working directory for anything that reads it.
    //
    // Safe for the profile database: SqliteUserProfilePort resolves against
    // user.home, not the working directory, so this does not move anyone's
    // saved profile.
    workingDir = rootProject.projectDir

    for (name in listOf("openfps.screenshot", "openfps.screenshotFrame",
                        "openfps.screenshotExit", "openfps.fpsLog")) {
        val value = providers.systemProperty(name).orNull
        if (value != null) {
            systemProperty(name, value)
        }
    }
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
