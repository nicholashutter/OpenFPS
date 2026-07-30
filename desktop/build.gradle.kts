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

    // The presenter, the welcome screen, the UI state machine and the input
    // accumulator — everything that needs libGDX but not a backend. Shared
    // with :android rather than duplicated; see gdxshared/build.gradle.kts.
    // It exposes :engine and libGDX core as `api`, so both remain on this
    // module's compile classpath through it as well as directly.
    implementation(project(":gdxshared"))

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

    // openfps.workers pins the worker pool (ThreadPoolFactory sizes it from the
    // processor count otherwise). It is forwarded for the same reason as the
    // rest: a -D that lands on the daemon would leave the pool auto-sized while
    // looking as though it had been pinned, which is the worst of both.
    // openfps.renderMode and openfps.renderFilter pick the internal render
    // resolution and how the finished frame is blitted up to the window. Both
    // are otherwise reachable only through the settings screen, which needs a
    // mouse — so without forwarding them, the two modes most in need of a "does
    // it flip? does it stretch?" capture would be the two nothing can capture.
    // openfps.autoWalk* holds a movement axis for the first N tics. It is the
    // only way an unattended run can walk into a wall: Gdx.input answers for a
    // physical keyboard, and GdxInputPort refuses to read the device unless the
    // cursor is caught and the window focused, neither of which a capture run
    // can promise. Without it, collision is the one feature in the game that
    // nothing but a human at a keyboard could ever demonstrate.
    // openfps.targetOutline turns the accessibility outline OFF, which is the
    // only direction that needs a property: it is on by default, so a capture of
    // the game WITHOUT it is the one nothing else can take. It is also the pair
    // to openfps.debugOverlay rather than a variant of it — the two switches are
    // deliberately separate and separately defaulted.
    // openfps.screenshotCount captures a RUN of consecutive frames from one
    // process. It is the only way to photograph anything that moves: a tracer
    // lives 8 tics and a puff of smoke 36, and launching the game once per frame
    // does not produce adjacent frames — each launch is its own process, so
    // "frame 300" of one run and "frame 301" of the next are not neighbours.
    // openfps.profile.db moves the SQLite profile somewhere other than
    // ~/.openfps/profile.db. It is forwarded for one specific reason: two peers
    // of a networked match on one machine are two processes, and SqliteUserProfilePort
    // resolves its path from user.home, so by default both would open the SAME
    // file. Two writers on one profile database is a corruption risk that would
    // surface as a mystery on the next launch rather than at the moment it
    // happened, and there is no other channel for the override -- an unforwarded
    // -D would land on the Gradle daemon and leave both peers quietly sharing.
    for (name in listOf("openfps.screenshot", "openfps.screenshotFrame",
                        "openfps.screenshotCount",
                        "openfps.screenshotExit", "openfps.fpsLog",
                        "openfps.workers", "openfps.debugOverlay",
                        "openfps.targetOutline",
                        "openfps.renderMode", "openfps.renderFilter",
                        "openfps.profile.db",
                        "openfps.autoWalkTics", "openfps.autoWalkForward",
                        "openfps.autoWalkStrafe")) {
        val value = providers.systemProperty(name).orNull
        if (value != null) {
            systemProperty(name, value)
        }
    }
}

// ---------------------------------------------------------------------------
// Native packaging — this is what gives the game a Windows process name.
//
// A running `gradlew :desktop:run` is a JVM, so Task Manager lists `java.exe`
// and always will: the window title and the taskbar icon are decoration on a
// process that is not ours. Nothing short of a native launcher changes that.
// jpackage (JDK 14+, and this project is on 17) is the supported way to get
// one — it produces OpenFPS.exe next to a trimmed runtime, and THAT is the
// process name in Task Manager.
//
//   gradlew :desktop:packageWindows
//   desktop/build/dist/OpenFPS/OpenFPS.exe
//
// The generated app-image is a directory, not an installer. That is on purpose:
// an installer needs WiX on PATH and is a different conversation, while an
// app-image runs from where it is built and is what you want for testing.
// ---------------------------------------------------------------------------

// Everything below resolves to a plain String or File at CONFIGURATION time and
// captures nothing else. The configuration cache refuses to serialise a task
// body that closes over the Project or over a Gradle script object, and both
// tasks did until this was written out longhand — `delete(...)` inside a
// doFirst is a Project method call, and a Provider captured by a lambda is a
// script reference. Resolving first is the supported shape.
val iconFile = layout.buildDirectory.file("icons/openfps.ico").get().asFile
val distDir = layout.buildDirectory.dir("dist").get().asFile
val stagingDir = layout.buildDirectory.dir("install/desktop/lib").get().asFile
val appImageDir = File(distDir, "OpenFPS")
val jpackageExe = File(System.getProperty("java.home"), "bin/jpackage").absolutePath
val launcherJar = "desktop-${project.version}.jar"
// jpackage on Windows wants a bare major[.minor[.patch]] and rejects anything
// else — "0.1.0-SNAPSHOT" fails with "contains invalid component [0-SNAPSHOT]",
// which is a real message from a real run and reads like a parser bug rather
// than a rule. Strip to the numeric prefix, and fall back to 1.0.0 if that
// leaves nothing, since an empty --app-version is also refused.
val appVersion = Regex("^[0-9]+(\\.[0-9]+)*")
    .find(project.version.toString())?.value ?: "1.0.0"

/**
 * Writes the window icon out as a .ico.
 *
 * The icon itself is generated by WindowIcon and needs no file at runtime; this
 * exists only because jpackage's --icon takes a path. See IconFileMain.
 */
val writeWindowsIcon by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Generates the Windows .ico from WindowIcon's design"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.openfps.desktop.IconFileMain")
    args(iconFile.absolutePath)
    outputs.file(iconFile)
}

/**
 * Clears the previous app-image.
 *
 * A whole task rather than a `doFirst` on the one below, and not by choice: a
 * Kotlin lambda in a build script holds a reference to the script object, which
 * the configuration cache cannot serialise however little the lambda body
 * actually uses. `Delete` is configured declaratively and captures nothing, so
 * it is the shape that survives.
 *
 * It is needed at all because jpackage refuses to overwrite an existing
 * app-image, and reports it in a way that reads like a permissions problem — so
 * without this the second run of `packageWindows` looks broken.
 */
val cleanAppImage by tasks.registering(Delete::class) {
    group = "distribution"
    description = "Removes the previous OpenFPS app-image so jpackage can write a new one"
    delete(appImageDir)
}

val packageWindows by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds OpenFPS.exe with jpackage, so the process is named OpenFPS"
    dependsOn(tasks.named("installDist"), writeWindowsIcon, cleanAppImage)

    commandLine(
        jpackageExe,
        "--type", "app-image",
        "--name", "OpenFPS",
        "--app-version", appVersion,
        "--vendor", "OpenFPS",
        "--description", "OpenFPS — a software-rendered arena shooter",
        "--input", stagingDir.absolutePath,
        "--main-jar", launcherJar,
        "--main-class", "com.openfps.desktop.DesktopLauncher",
        "--icon", iconFile.absolutePath,
        "--dest", distDir.absolutePath
    )
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
