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

// Renders one frame of a model to a PNG, with no window and no GL.
//
// This is how the software rasterizer is verified where there is no display —
// see RenderPreviewMain's Javadoc. Every argument passes straight through, so
// the task adds nothing but a classpath:
//
//   gradlew :tools:renderPreview --args="--out=C:\tmp\frame.png"
//   gradlew :tools:renderPreview --args="--out=C:\tmp\f.png --cull=NONE --width=1920"
//
// Deliberately NOT wired into `build`: it writes a file, and the output path is
// never defaulted into the repository — docs/ASSETS.md § 6 keeps generated art
// out of git.
tasks.register<JavaExec>("renderPreview") {
    group = "openfps"
    description = "Renders one frame of a ModelFormat model to a PNG. Headless."

    mainClass.set("com.openfps.tools.RenderPreviewMain")
    classpath = sourceSets["main"].runtimeClasspath

    // JavaExec defaults its working directory to the SUBPROJECT directory, so
    // an unqualified `--model=assets/models/x.ofm` resolved against tools/ and
    // failed with a NoSuchFileException naming a path the user never typed.
    // Every other task here builds absolute paths through
    // `rootProject.layout.projectDirectory`; this one passes `--args` straight
    // through, so it needs the working directory moved instead. Relative paths
    // now mean what the docs say they mean — relative to the repository root.
    workingDir = rootProject.projectDir
}

// Renders the WHOLE first-person demo — room, props and held weapon — to PNGs
// with no window and no GL.
//
// This is how the demo is verified where there is no display, and it is a
// different question from renderPreview's: that one orbits a single model, this
// one builds the real DemoScene, places the view-space viewmodel, and drives
// the camera by feeding InputState through PlayerController. See
// DemoPreviewMain's Javadoc.
//
//   gradlew :tools:demoPreview -PdemoOut=C:\tmp\demo
//   gradlew :tools:demoPreview -PdemoOut=C:\tmp\demo -PdemoThreads=8 -PdemoFrames=200
//
// Deliberately NOT wired into `build`: it writes files, and the output path is
// never defaulted into the repository — docs/ASSETS.md § 6 keeps generated art
// out of git.
tasks.register<JavaExec>("demoPreview") {
    group = "openfps"
    description = "Renders the first-person demo scene, viewmodel included, to PNGs. Headless."

    mainClass.set("com.openfps.tools.DemoPreviewMain")
    classpath = sourceSets["main"].runtimeClasspath

    val outDir = providers.gradleProperty("demoOut")
    val assetsDir = providers.gradleProperty("demoAssets").orElse("assets/models")
    val threads = providers.gradleProperty("demoThreads").orElse("0")
    val frames = providers.gradleProperty("demoFrames").orElse("0")
    val width = providers.gradleProperty("demoWidth").orElse("1280")
    val height = providers.gradleProperty("demoHeight").orElse("720")
    // Which shot to render, and therefore which camera pose --frames is timed
    // from: the measurement loop reuses whatever camera the last shot left in
    // place. Without this the only poseable benchmark was shot 04, which looks
    // at a corner and so flatters anything that culls; comparing it against
    // shot 01, which looks down the whole room, is what separates a real saving
    // from a lucky pose.
    val shot = providers.gradleProperty("demoShot").orElse("")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val target = outDir.orNull
            ?: throw GradleException(
                "-PdemoOut=<directory> is required: it is where the PNGs are written.\n" +
                "  Point it OUTSIDE the repository — docs/ASSETS.md § 6 keeps generated art\n" +
                "  out of git."
            )
        listOf(
            "--outDir=" + File(target).absolutePath,
            "--assets=" + rootDirectory.dir(assetsDir.get()).asFile.absolutePath,
            "--threads=" + threads.get(),
            "--frames=" + frames.get(),
            "--width=" + width.get(),
            "--height=" + height.get(),
            "--shot=" + shot.get()
        )
    })
}

// Renders every Markdown file the repository documents itself with into a
// self-contained static site.
//
//   .\gradlew.bat :tools:buildDocsSite
//   .\gradlew.bat :tools:buildDocsSite -PdocsOut=some/other/dir
//
// The generator lives here rather than in a script for the same reason the
// model converter does: this module already has a Java toolchain, Checkstyle
// and -Werror pointed at it, and `verifyToolsIsolation` already proves none of
// it reaches a shipped runtime classpath. A script would be the only build step
// needing a toolchain CI does not already install.
//
// Deliberately NOT wired into `build`. It writes into the working tree, and a
// broken cross-document link — which this task fails on, by design — should not
// stop anyone compiling the engine.
tasks.register<JavaExec>("buildDocsSite") {
    group = "openfps"
    description = "Generates the static documentation site from the repository's Markdown."

    mainClass.set("com.openfps.tools.docs.DocsSiteMain")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("docsOut").orElse("docs/site")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            rootDirectory.asFile.absolutePath,
            rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
    })
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

// ---------------------------------------------------------------------------
// The first-person demo's model set
// ---------------------------------------------------------------------------
// One command rebuilds every .ofm the demo needs:
//
//   .\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=C:\path\to\extracted
//
// `kenneyRaw` names a directory holding each CC0 pack UNZIPPED into a
// subdirectory of its own, matching the pack names below:
//
//   <kenneyRaw>/blaster-kit/Models/GLB format/...
//   <kenneyRaw>/prototype-kit/Models/GLB format/...
//
// The download itself is a MANUAL step and stays one. docs/ASSETS.md § 6 forbids
// build-time third-party fetches outright: those URLs are unpinnable, their
// uptime is not ours, and CI has to stay hermetic. docs/DEMO_ASSETS.md carries
// the two URLs, their SHA-256s, and the licence check that must accompany them.
//
// Without -PkenneyRaw the staging step warns and skips, and DemoAssetsMain
// falls through to ProceduralRoom — a generated greybox room, logged loudly as
// generated, so a clone with no packs still has a floor to stand on.

// Which pieces the demo actually uses, and which pack each comes from. Kept
// here rather than in Java because it is curation, not logic: changing the
// selection should not mean recompiling a converter.
val demoWeaponPack = "blaster-kit"
val demoWeaponPieces = listOf("blaster-b")
val demoLevelPack = "prototype-kit"
val demoLevelPieces = listOf(
    "floor-square",   // floor tile
    "wall",           // plain wall segment
    "wall-corner",    // corner, so a room closes
    "wall-doorway",   // a way out, which is what makes it read as a place
    "shape-slope",    // ramp
    "stairs",
    "column",
    "crate"           // prop, for parallax and a scale reference
)

// Kenney's GLBs reference their atlas by RELATIVE URI — `Textures/colormap.png`
// — so the atlas must sit beside each .glb or GltfAsset cannot resolve it. That
// is a staging obligation, not a converter bug: the glTF 2.0 specification
// resolves a relative URI against the referring document, and honouring that is
// correct behaviour. Hence every staged directory gets its own copy of the
// pack's atlas.
val demoAtlasDirectory = "Textures"
val demoAtlasFile = "colormap.png"

tasks.register("stageDemoAssets") {
    group = "openfps"
    description = "Copies the curated CC0 demo selection out of extracted packs into assets/gltf."

    // Captured at configuration time: the task action must hold no project
    // reference, because the configuration cache is on.
    val rawRoot = providers.gradleProperty("kenneyRaw")
    val stageRoot = rootProject.layout.projectDirectory.dir("assets/gltf").asFile
    val groups = listOf(
        Triple("weapon", demoWeaponPack, demoWeaponPieces),
        Triple("level", demoLevelPack, demoLevelPieces)
    )
    val atlasDirectory = demoAtlasDirectory
    val atlasFile = demoAtlasFile

    doLast {
        val raw = rawRoot.orNull
        if (raw == null) {
            logger.warn(
                "No -PkenneyRaw given — skipping staging.\n" +
                "  Download and unzip the CC0 packs listed in docs/DEMO_ASSETS.md, then rerun\n" +
                "  with -PkenneyRaw=<directory containing the unzipped packs>.\n" +
                "  Without them the demo falls back to generated greybox geometry."
            )
            return@doLast
        }

        val rawDirectory = File(raw)
        if (!rawDirectory.isDirectory) {
            throw GradleException("-PkenneyRaw=$raw is not a directory.")
        }

        for (group in groups) {
            val source = File(rawDirectory, "${group.second}/Models/GLB format")
            if (!source.isDirectory) {
                throw GradleException(
                    "Pack '${group.second}' not found at $source.\n" +
                    "  Unzip it so that '<kenneyRaw>/${group.second}/Models/GLB format' exists.\n" +
                    "  See docs/DEMO_ASSETS.md for the URL, the SHA-256 and the licence check."
                )
            }

            val target = File(stageRoot, group.first)
            target.mkdirs()
            for (piece in group.third) {
                val glb = File(source, "$piece.glb")
                if (!glb.isFile) {
                    throw GradleException("'$piece.glb' is not in ${group.second} at $source.")
                }
                glb.copyTo(File(target, "$piece.glb"), overwrite = true)
            }

            val atlas = File(source, "$atlasDirectory/$atlasFile")
            if (!atlas.isFile) {
                throw GradleException(
                    "Atlas $atlas is missing — the staged GLBs reference it by relative URI."
                )
            }
            val atlasTarget = File(target, atlasDirectory)
            atlasTarget.mkdirs()
            atlas.copyTo(File(atlasTarget, atlasFile), overwrite = true)

            logger.lifecycle(
                "Staged ${group.third.size} model(s) from ${group.second} into $target"
            )
        }
    }
}

// Converts the staged tree, falls back to generated geometry when nothing was
// staged, then reads every .ofm back through the runtime's own ModelFormat and
// reports it against the docs/ASSETS.md § 5 budget. Fails the build if any
// model does not parse, holds no geometry, or is over budget.
//
// Deliberately NOT wired into `build`: same reason as convertModels.
tasks.register<JavaExec>("regenerateDemoAssets") {
    group = "openfps"
    description = "Rebuilds and verifies every demo .ofm from its sources."

    dependsOn("stageDemoAssets")

    mainClass.set("com.openfps.tools.DemoAssetsMain")
    classpath = sourceSets["main"].runtimeClasspath

    val inputDir = providers.gradleProperty("modelsIn").orElse("assets/gltf")
    val outputDir = providers.gradleProperty("modelsOut").orElse("assets/models")
    val rootDirectory = rootProject.layout.projectDirectory
    val forceFallback = providers.gradleProperty("forceFallback").isPresent

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--gltf=" + rootDirectory.dir(inputDir.get()).asFile.absolutePath,
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        if (forceFallback) {
            arguments.add("--forceFallback")
        }
        arguments
    })
}

// Verification on its own, for re-checking a payload that is already converted.
tasks.register<JavaExec>("verifyModels") {
    group = "openfps"
    description = "Reads every .ofm back through ModelFormat and reports it against the budget."

    mainClass.set("com.openfps.tools.DemoAssetsMain")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("modelsOut").orElse("assets/models")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath)
    })
}
