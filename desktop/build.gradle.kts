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
