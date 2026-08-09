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

    // JavaParser 3.26.4 — LGPL-2.1-or-later
    // (https://github.com/javaparser/javaparser/blob/master/LICENSE).
    // The blank-line formatter (BlankLineFormatter, see STYLE.md § 14) needs
    // statement ranges from an AST; a regex pass would mangle generics,
    // string literals and annotations. JavaParser is a parse-only dep here
    // and never appears on a runtime classpath — the LGPL dynamic-link
    // exception is irrelevant because nothing ships this.
    implementation("com.github.javaparser:javaparser-core:3.26.4")

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
// Two blasters, and they must stay visibly different from each other: blaster-b
// is the player's viewmodel and blaster-p is what the bots carry. b is a compact
// orange pistol 0.42 units long; p is a green two-handed carbine at 0.86. Several
// of the pack's eighteen blasters are near-duplicates of b with a different grip,
// and picking one of those would have been a change nobody could see across a
// room. Chosen off the pack's own preview renders, not by taking the next letter.
val demoWeaponPieces = listOf("blaster-b", "blaster-p")
val demoLevelPack = "prototype-kit"
val demoLevelPieces = listOf(
    "floor-square",       // floor tile
    "wall",               // plain wall segment
    "wall-corner",        // corner, so a room closes
    "wall-doorway",       // a way out, which is what makes it read as a place
    "shape-slope",        // ramp
    "stairs",
    "column",
    "crate",              // prop, for parallax and a scale reference
    // Below this line: added for the lane-map set (LaneMapLibrary). Every one
    // of these is real Kenney art from the same already-vetted CC0 pack, not a
    // new licence to track.
    "wall-low",           // crouch-height cover — the piece a lane map is
                           // built around; sightlines break at a body's chest
                           // rather than only at full walls
    "wall-corner-low",    // matching corner, so low cover can turn
    "wall-window-medium", // a sightline between two lanes that is not a full
                           // doorway — narrower, and blockable by a body in it
    "wall-diagonal",      // an authored 45-degree wall face; PhysicsWorld still
                           // blocks its full axis-aligned bounding square (see
                           // LaneMapPhysics), so this is a visual angle only
    "column-low",         // waist-height cover to break a lane's centre line
    "stairs-small",       // a compact rise for a half-flight, not a full course
    "floor-thick",        // an elevated ledge — placed a course up, it is the
                           // high ground a 3-lane map's mid lane wants
    "shape-cube-half",    // half-height block, modular cover
    "vehicle",            // a wrecked-jeep-style centrepiece for a lane's open
                           // ground, the same role Nuketown's car plays
    "crate-color"         // a second crate look, free — same mesh budget, a
                           // different bake, so two stacks of crates read as
                           // two different stacks rather than one repeated
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

// Builds the Cornerstone map's level model: a procedurally generated 320x320
// urban block with three lanes, perimeter walls, four landmark buildings and
// a row of crates. Output goes to engine/src/main/resources/maps/cornerstone/
// and is committed via `git add -f` (the `*.ofm` pattern in .gitignore is
// overridden for this small committed fixture).
//
//   .\gradlew.bat :tools:buildCornerstoneMap
//
//   .\gradlew.bat :tools:buildCornerstoneMap -PcornerstoneAtlas=<path>
//
// The optional `-PcornerstoneAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the committed model uses Kenney
// floor / wall colours rather than the procedural generator. Without the
// atlas, the builder falls back to the pre-Pass 2 procedural textures.
//
// Deliberately NOT wired into `build`. It writes into the working tree, and a
// model that does not parse or that goes over budget should not stop anyone
// compiling the engine.
tasks.register<JavaExec>("buildCornerstoneMap") {
    group = "openfps"
    description = "Generates the Cornerstone map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.CornerstoneMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("cornerstoneOut")
        .orElse("engine/src/main/resources/maps/cornerstone")
    val atlasPath = providers.gradleProperty("cornerstoneAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Refinery map's level model: a procedurally generated 320x320
// industrial complex with three lanes, multi-level catwalks, and four named
// landmarks (Distillation Tower, Process Hall, Boiler, Control Room).
// Output goes to engine/src/main/resources/maps/refinery/ and is committed
// via `git add -f` (the same exception the Cornerstone model uses).
//
//   .\gradlew.bat :tools:buildRefineryMap
//
//   .\gradlew.bat :tools:buildRefineryMap -PrefineryAtlas=<path>
//
// The optional `-PrefineryAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the floor / wall textures come
// from the pack (Pass 5 Kenney-ize). Without the atlas, the builder uses
// the pre-Pass 5 procedural generator.
//
// Deliberately NOT wired into `build`. Same reason as buildCornerstoneMap.
tasks.register<JavaExec>("buildRefineryMap") {
    group = "openfps"
    description = "Generates the Refinery map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.RefineryMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("refineryOut")
        .orElse("engine/src/main/resources/maps/refinery")
    val atlasPath = providers.gradleProperty("refineryAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Crossroads map's level model: a procedurally generated
// 320x320 desert town with three lanes, a central plaza at the four-way
// crossroads, low sandstone buildings, weathered wood wells, and
// sparse cover. Output goes to engine/src/main/resources/maps/crossroads/
// and is committed via `git add -f` (the same exception the cornerstone
// and refinery models use).
//
//   .\gradlew.bat :tools:buildCrossroadsMap
//
//   .\gradlew.bat :tools:buildCrossroadsMap -PcrossroadsAtlas=<path>
//
// The optional `-PcrossroadsAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the floor / wall textures come
// from the pack (Pass 5 Kenney-ize). Without the atlas, the builder uses
// the pre-Pass 5 procedural generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildCrossroadsMap") {
    group = "openfps"
    description = "Generates the Crossroads map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.CrossroadsMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("crossroadsOut")
        .orElse("engine/src/main/resources/maps/crossroads")
    val atlasPath = providers.gradleProperty("crossroadsAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Icebridge map's level model: a procedurally generated
// 320x320 polar rest stop with two long east-west frozen bridges over
// a frozen ravine, a service building anchoring the south, fuel depots
// on the North Bridge, and snowdrift cover on the ravine floor. Output
// goes to engine/src/main/resources/maps/arctic-station/ and is
// committed via `git add -f` (the same exception the cornerstone,
// refinery, and crossroads models use).
//
//   .\gradlew.bat :tools:buildArcticStationMap
//
//   .\gradlew.bat :tools:buildArcticStationMap -ParcticStationAtlas=<path>
//
// The optional `-ParcticStationAtlas=` argument points the builder at
// the Kenney Prototype Kit's colormap.png so the floor / wall textures
// come from the pack (Pass 5 Kenney-ize). Without the atlas, the
// builder uses the pre-Pass 5 procedural generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildArcticStationMap") {
    group = "openfps"
    description = "Generates the Icebridge map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.ArcticStationMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("arcticStationOut")
        .orElse("engine/src/main/resources/maps/arctic-station")
    val atlasPath = providers.gradleProperty("arcticStationAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Foundry map's level model: a 320x320 heavy-machinery
// foundry with three machine halls (cast-metal shop, assembly floor,
// cooling room) and a system of mid-level gantries connecting them.
// Output goes to engine/src/main/resources/maps/foundry/ and is
// committed via `git add -f` (same exception as the other maps).
//
//   .\gradlew.bat :tools:buildFoundryMap
//
//   .\gradlew.bat :tools:buildFoundryMap -PfoundryAtlas=<path>
//
// The optional `-PfoundryAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the floor / wall textures
// come from the pack. Without it, the builder uses the procedural
// generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildFoundryMap") {
    group = "openfps"
    description = "Generates the Foundry (Industrial Hardpoint) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.FoundryMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("foundryOut")
        .orElse("engine/src/main/resources/maps/foundry")
    val atlasPath = providers.gradleProperty("foundryAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Mesa map's level model: a 320x320 desert plateau with a
// raised mesa top covering the centre, a low-roofed cave at the
// south end, a south ramp and a north switchback. Output goes to
// engine/src/main/resources/maps/mesa/ and is committed via `git
// add -f` (same exception as the other maps).
//
//   .\gradlew.bat :tools:buildMesaMap
//
//   .\gradlew.bat :tools:buildMesaMap -PmesaAtlas=<path>
//
// The optional `-PmesaAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the floor / wall textures
// come from the pack. Without it, the builder uses the procedural
// generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildMesaMap") {
    group = "openfps"
    description = "Generates the Mesa (Desert Hardpoint) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.MesaMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("mesaOut")
        .orElse("engine/src/main/resources/maps/mesa")
    val atlasPath = providers.gradleProperty("mesaAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Subzero map's level model: a 320x320 polar research
// outpost with three small sheet-metal buildings (the Generator
// Shed, the Operations Trailer, the Fuel Depot) connected by a
// system of snow-walled trenches. Output goes to
// engine/src/main/resources/maps/arctic-hp/ and is committed via
// `git add -f` (same exception as the other maps).
//
//   .\gradlew.bat :tools:buildSubzeroMap
//
//   .\gradlew.bat :tools:buildSubzeroMap -PsubzeroAtlas=<path>
//
// The optional `-PsubzeroAtlas=` argument points the builder at the
// Kenney Prototype Kit's colormap.png so the floor / wall textures
// come from the pack. Without it, the builder uses the procedural
// generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildSubzeroMap") {
    group = "openfps"
    description = "Generates the Subzero (Arctic Hardpoint) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.SubzeroMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("subzeroOut")
        .orElse("engine/src/main/resources/maps/arctic-hp")
    val atlasPath = providers.gradleProperty("subzeroAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Overpass map's level model: a 320x320 highway interchange
// with two elevated overpasses, a service road, and a control building.
// Output goes to engine/src/main/resources/maps/overpass/ and is
// committed via `git add -f` (same exception as the other maps).
//
//   .\gradlew.bat :tools:buildOverpassMap
//
//   .\gradlew.bat :tools:buildOverpassMap -PoverpassAtlas=<path>
//
// Like the Cornerstone task, the optional `-PoverpassAtlas=` argument
// points the builder at the Kenney Prototype Kit's colormap.png so the
// floor / wall textures come from the pack. Without it, the builder
// uses the procedural generator.
//
// Deliberately NOT wired into `build`. Same reason as the other buildMap
// tasks.
tasks.register<JavaExec>("buildOverpassMap") {
    group = "openfps"
    description = "Generates the Overpass (Hardpoint) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.OverpassMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("overpassOut")
        .orElse("engine/src/main/resources/maps/overpass")
    val atlasPath = providers.gradleProperty("overpassAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Tripoint map's level model: a 320x320 three-way
// intersection with three flags and three approach streets. Output
// goes to engine/src/main/resources/maps/tripoint/ and is committed
// via `git add -f` (same exception).
//
//   .\gradlew.bat :tools:buildTripointMap
//
//   .\gradlew.bat :tools:buildTripointMap -PtripointAtlas=<path>
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildTripointMap") {
    group = "openfps"
    description = "Generates the Tripoint (Domination) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.TripointMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("tripointOut")
        .orElse("engine/src/main/resources/maps/tripoint")
    val atlasPath = providers.gradleProperty("tripointAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Extraction map's level model: a 320x320 urban block split
// by a long boulevard, with two bases (one per team) and flanking
// cover walls. Output goes to engine/src/main/resources/maps/extraction/
// and is committed via `git add -f` (same exception).
//
//   .\gradlew.bat :tools:buildExtractionMap
//
//   .\gradlew.bat :tools:buildExtractionMap -PextractionAtlas=<path>
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildExtractionMap") {
    group = "openfps"
    description = "Generates the Extraction (CTF) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.ExtractionMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("extractionOut")
        .orElse("engine/src/main/resources/maps/extraction")
    val atlasPath = providers.gradleProperty("extractionAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Pipeline map's level model: a 320x320 industrial
// pipeline pumping station with three long east-west pipelines
// (z=64, z=160, z=256) and three control valves. Three catwalks
// at y=64 run north-south alongside the pipelines. Output goes
// to engine/src/main/resources/maps/pipeline/ and is committed via
// `git add -f` (same exception).
//
//   .\gradlew.bat :tools:buildPipelineMap
//
//   .\gradlew.bat :tools:buildPipelineMap -PpipelineAtlas=<path>
//
// The optional `-PpipelineAtlas=` argument points the builder at
// the Kenney Prototype Kit's colormap.png so the floor / wall
// textures come from the pack. Without it, the builder uses the
// procedural generator.
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildPipelineMap") {
    group = "openfps"
    description = "Generates the Pipeline (Industrial Domination) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.PipelineMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("pipelineOut")
        .orElse("engine/src/main/resources/maps/pipeline")
    val atlasPath = providers.gradleProperty("pipelineAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Sandbar map's level model: a 320x320 wide, shallow
// canyon with three flat-topped sandstone buttes at z=64, z=160,
// and z=256. Each butte has a single 8-tread ramp on the east side.
// A dry riverbed runs through the centre. Output goes to
// engine/src/main/resources/maps/sandbar/ and is committed via
// `git add -f` (same exception).
//
//   .\gradlew.bat :tools:buildSandbarMap
//
//   .\gradlew.bat :tools:buildSandbarMap -PsandbarAtlas=<path>
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildSandbarMap") {
    group = "openfps"
    description = "Generates the Sandbar (Desert Domination) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.SandbarMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("sandbarOut")
        .orElse("engine/src/main/resources/maps/sandbar")
    val atlasPath = providers.gradleProperty("sandbarAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Arctic-Dom (Frostline) map's level model: a 320x320
// polar ice road with three flag platforms at z=80, z=160, and
// z=240. Each platform is a 16x16 raised ice block with a radar
// mast. The central ice road runs east-west at y=0, z=80..240.
// Output goes to engine/src/main/resources/maps/arctic-dom/ and is
// committed via `git add -f` (same exception).
//
//   .\gradlew.bat :tools:buildArcticDomMap
//
//   .\gradlew.bat :tools:buildArcticDomMap -ParcticDomAtlas=<path>
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildArcticDomMap") {
    group = "openfps"
    description = "Generates the Arctic-Dom (Arctic Domination) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.ArcticDomMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("arcticDomOut")
        .orElse("engine/src/main/resources/maps/arctic-dom")
    val atlasPath = providers.gradleProperty("arcticDomAtlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val arguments = mutableListOf(
            "--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + File(atlas).absolutePath)
        }
        arguments
    })
}

// Builds the Storage map's level model: a 320x320 chemical storage
// facility with two warehouse buildings (one per team) at opposite
// ends, and a maze of eight storage tanks in the centre. Output
// goes to engine/src/main/resources/maps/storage/ and is committed
// via `git add -f`.
//
//   .\gradlew.bat :tools:buildStorageMap
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildStorageMap") {
    group = "openfps"
    description = "Generates the Storage (Industrial CTF) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.StorageMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("storageOut")
        .orElse("engine/src/main/resources/maps/storage")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath)
    })
}

// Builds the Stronghold map's level model: a 320x320 sandstone fortress
// with two gate towers, four corner towers, a central courtyard, and
// two flanking cliff walls. Output goes to
// engine/src/main/resources/maps/stronghold/ and is committed via
// `git add -f`.
//
//   .\gradlew.bat :tools:buildStrongholdMap
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildStrongholdMap") {
    group = "openfps"
    description = "Generates the Stronghold (Desert Ravine CTF) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.StrongholdMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("strongholdOut")
        .orElse("engine/src/main/resources/maps/stronghold")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath)
    })
}

// Builds the Coldfront map's level model: a 320x320 polar-research
// base split across two sides of a frozen river. RED base on the west
// bank, BLUE base on the east bank, with watchtowers watching the
// river. Output goes to engine/src/main/resources/maps/coldfront/
// and is committed via `git add -f`.
//
//   .\gradlew.bat :tools:buildColdfrontMap
//
// Deliberately NOT wired into `build`. Same reason as the other
// buildMap tasks.
tasks.register<JavaExec>("buildColdfrontMap") {
    group = "openfps"
    description = "Generates the Coldfront (Arctic Station CTF) map's level.ofm from the procedural builder."

    mainClass.set("com.openfps.tools.ColdfrontMapBuilder")
    classpath = sourceSets["main"].runtimeClasspath

    val outputDir = providers.gradleProperty("coldfrontOut")
        .orElse("engine/src/main/resources/maps/coldfront")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--out=" + rootDirectory.dir(outputDir.get()).asFile.absolutePath)
    })
}

// Builds a map from a JSON config file. The new generator lives in
// com.openfps.tools.mapgen, and this task is its Gradle seam: a config file
// + a map id are all the user has to provide.
//
//   .\gradlew.bat :tools:buildMapFromConfig -Pconfig=tools/config/maps/sample-cornerstone.json -PmapId=sample-cornerstone
//   .\gradlew.bat :tools:buildMapFromConfig -Pconfig=... -PmapId=... -Patlas=C:\path\to\colormap.png
//
// `-Pconfig` is the JSON. `-PmapId` names the output subdirectory under
// engine/src/main/resources/maps (the same layout the existing 13
// hand-written builders target). `-Patlas` is optional; without it the
// generator uses procedural solid-colour tiles.
//
// Deliberately NOT wired into `build`. The same reason the other buildMap
// tasks are not: it writes into the working tree, and a config that doesn't
// produce a valid .ofm should not stop anyone compiling the engine.
tasks.register<JavaExec>("buildMapFromConfig") {
    group = "openfps"
    description = "Generates a map level.ofm from a JSON config file using the config-driven generator."

    mainClass.set("com.openfps.tools.mapgen.MapGenMain")
    classpath = sourceSets["main"].runtimeClasspath

    val configPath = providers.gradleProperty("config")
    val mapId = providers.gradleProperty("mapId")
    val atlasPath = providers.gradleProperty("atlas")
    val rootDirectory = rootProject.layout.projectDirectory

    argumentProviders.add(CommandLineArgumentProvider {
        val cfg = configPath.orNull
            ?: throw GradleException(
                "-Pconfig=<path-to-config.json> is required."
            )
        val id = mapId.orNull
            ?: throw GradleException(
                "-PmapId=<id> is required (it names the output subdirectory)."
            )
        val configFile = rootDirectory.file(cfg).asFile
        val outputDir = rootDirectory.dir("engine/src/main/resources/maps").dir(id)
        val arguments = mutableListOf(
            "--config=" + configFile.absolutePath,
            "--out=" + outputDir.asFile.absolutePath
        )
        val atlas = atlasPath.orNull
        if (atlas != null)
        {
            arguments.add("--atlas=" + rootDirectory.file(atlas).asFile.absolutePath)
        }
        arguments
    })
}

// Inserts exactly one blank line between every pair of consecutive statements
// inside every BlockStmt, across an arbitrary set of source roots. Implements
// STYLE.md § 14 mechanically: see BlankLineFormatter's Javadoc for the full
// algorithm.
//
// With no extra arguments the task processes every module's src/ tree under
// the repository root:
//
//   .\gradlew.bat :tools:formatBlankLines
//
// For an explicit subset, pass paths as --args (single string, space- or
// comma-separated; the path may be a .java file or a directory, walked
// recursively with build/, bin/ and .gradle/ subtrees skipped):
//
//   .\gradlew.bat :tools:formatBlankLines --args="engine/src,tools/src"
//
// The formatter preserves line endings, comments and every byte outside the
// rewritten gaps.
//
// Deliberately NOT wired into `build`: it rewrites source files in place,
// and a tool that touches the entire .java tree should not run as a
// side-effect of compiling. Run it explicitly, review the diff, commit.
tasks.register<JavaExec>("formatBlankLines") {
    group = "openfps"
    description = "Inserts a blank line between every pair of consecutive statements (STYLE.md § 14)."

    mainClass.set("com.openfps.tools.format.BlankLineFormatter")
    classpath = sourceSets["main"].runtimeClasspath

    val rootDirectory = rootProject.projectDir
    workingDir = rootDirectory

    val userArgs = providers.gradleProperty("formatArgs")
    argumentProviders.add(CommandLineArgumentProvider {
        val user = userArgs.orNull
        if (user != null && user.isNotBlank())
        {
            user.split("[,\\s]+".toRegex())
                .filter { it.isNotBlank() }
        }
        else
        {
            // No -PformatArgs given: default to every module's src/ tree.
            listOf(
                "engine/src",
                "gdxshared/src",
                "desktop/src",
                "android/src",
                "tools/src"
            )
        }
    })
}

