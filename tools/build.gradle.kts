// Build-time asset tooling. NOTHING HERE IS EVER SHIPPED.
//
// This module holds GltfConverter: upstream glTF/GLB in, the engine's flat
// binary ModelFormat out. docs/ASSETS.md § 4 is the reason it exists — glTF is
// not parsed at runtime, because a software rasterizer's scarcest resource is
// per-frame CPU and its cheapest is build-time CPU, so triangulation, texture
// decode, mip generation and budget enforcement all move offline.
//
// The dependency edge runs ONE WAY: :tools -> :engine. It exists so the
// converter compiles against ModelFormat's layout constants rather than
// keeping a second copy of the byte offsets. Nothing depends on :tools, so
// neither this module nor Gson can reach :engine's, :desktop's or :android's
// runtime classpath. The root project's `verifyToolsIsolation` task proves
// that mechanically and is wired into `check`.

plugins {
    id("java")
    id("checkstyle")
}

repositories {
    mavenCentral()
}

dependencies {
    // One-way edge to the engine, for ModelFormat's layout constants, Rgba's
    // pixel packing and Mat4's transform math. Reusing those is the point:
    // a second definition of the vertex stride or of the RGBA byte order is
    // precisely the divergence AGENTS.md rule 1 exists to prevent.
    implementation(project(":engine"))

    // Gson 2.11.0 — Apache-2.0 (https://github.com/google/gson/blob/main/LICENSE).
    // glTF is JSON plus binary buffers. Parsing JSON by hand is possible but is
    // pure liability for a build tool, and docs/ASSETS.md § 4 explicitly
    // sanctions a JSON/glTF library here BECAUSE build-time tooling ships
    // nothing. Apache-2.0 satisfies docs/ASSETS.md § 3's accepted list.
    // Gson only parses the JSON chunk; the GLB container, the accessor
    // decoding and the scene-graph walk are written here against the Khronos
    // glTF 2.0 specification.
    implementation("com.google.code.gson:gson:2.11.0")

    // A logging backend, so the converter's diagnostics actually appear when
    // Gradle runs it. slf4j-api arrives transitively from :engine's `api`.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
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

// The isolation proof runs as part of this module's `check`, so a plain
// `gradlew build` fails the moment :tools or Gson reaches a shipped classpath.
tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyToolsIsolation"))
}

// Converts every .gltf / .glb under the input directory into .ofm models.
//
// Deliberately NOT wired into `build`, for the same reason `fetchAssets` is
// not: there is no asset payload in a clean clone and CI must stay hermetic.
// Run it explicitly after `fetchAssets`:
//
//   .\gradlew.bat :tools:convertModels
//   .\gradlew.bat :tools:convertModels -PmodelsIn=some/dir -PmodelsOut=other/dir
//
// If the input directory does not exist the task reports that and stops
// without failing — an opt-in step should not break a build nobody asked to
// run it in. A budget violation, by contrast, fails hard: see AssetBudget.
tasks.register<JavaExec>("convertModels") {
    group = "openfps"
    description = "Converts glTF/GLB models to the runtime ModelFormat, enforcing the asset budget."

    mainClass.set("com.openfps.tools.GltfConverterMain")
    classpath = sourceSets["main"].runtimeClasspath

    val inputDir = providers.gradleProperty("modelsIn").orElse("assets/gltf")
    val outputDir = providers.gradleProperty("modelsOut").orElse("assets/models")
    val rootDir = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            rootDir.dir(inputDir.get()).asFile.absolutePath,
            rootDir.dir(outputDir.get()).asFile.absolutePath
        )
    })
}
