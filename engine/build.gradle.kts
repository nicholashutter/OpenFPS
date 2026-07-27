// The engine core. Pure Java 17, no platform dependencies — this module must
// compile and test without a window, a display, or an Android SDK. Anything
// platform-specific belongs in :desktop or :android behind a HAL port.

plugins {
    id("java-library")
    id("application")
    id("checkstyle")
}

repositories {
    mavenCentral()
}

dependencies {
    // Logging — industry standard: SLF4J facade + Logback backend
    // SLF4J 2.0.x is the modern line (no more javax.*)
    // Logback 1.5.x is the canonical backend
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // SQLite — Xerial SQLite JDBC (dual Apache-2.0 / BSD-2-Clause).
    // Works on every JVM 17+ platform: Windows, Linux, macOS, Android (with shim).
    // NOT pure Java: the jar bundles precompiled SQLite natives for ~20 platform
    // triplets (win/mac/linux-glibc/linux-musl/freebsd/android × x86, x86_64,
    // armv5/6/7, arm64, ppc64, riscv64). That is why it needs no per-platform
    // classifier — and also why it alone accounts for most of the ~15 MB
    // distribution archive. Worth remembering when budgeting download size.
    // Source: https://github.com/xerial/sqlite-jdbc
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // JUnit 5.11.4 — latest in the 5.11 LTS line
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // AssertJ for more readable test assertions
    testImplementation("org.assertj:assertj-core:3.26.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Headless entry point. The desktop windowed launcher lives in :desktop.
application {
    mainClass.set("com.openfps.engine.core.EngineMain")
}

// Checkstyle enforces STYLE.md. The config stays at the repository root so
// every module shares one ruleset — hence rootProject.file, not file(), which
// would resolve against this module's directory.
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

// Ship the third-party notice with the binary. CC0 game data imposes no
// attribution requirement, but the bundled libraries (Logback, sqlite-jdbc) do.
distributions {
    main {
        contents {
            from(rootProject.file("NOTICE"))
            from(rootProject.file("LICENSE"))
        }
    }
}
