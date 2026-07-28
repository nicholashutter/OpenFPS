// Android launcher. The only module that applies the Android Gradle Plugin.
//
// The SDK levels here are forced, not chosen: libGDX 1.14.2's Android
// backend depends on androidx.core 1.17.0, which refuses to be compiled
// against anything below compileSdk 36 and demands AGP >= 8.9.1. See the
// version chain comment in settings.gradle.kts before changing either.
//
// This module needs an Android SDK. `:engine` deliberately does not, so a
// machine with no SDK can still build and test the whole engine core:
//     gradlew :engine:build
// The Android SDK location comes from the ANDROID_HOME environment variable
// or local.properties; neither is committed.

plugins {
    id("com.android.application")
}

repositories {
    mavenCentral()
    // AndroidX and the Android platform artifacts AGP pulls in.
    google()
}

val gdxVersion = "1.14.2"
val roomVersion = "2.8.4"

// ---------------------------------------------------------------------------
// Model assets — what makes the Android build a game rather than a menu
// ---------------------------------------------------------------------------
// The demo's models live at the repository root in assets/models, written by
// `gradlew :tools:regenerateDemoAssets`. Desktop reads them straight off disk.
// An APK cannot: the app has no access to the developer's filesystem, so the
// files have to be INSIDE it, under assets/, where ApkModelSource reads them
// through the platform AssetManager.
//
// Staged into a generated directory rather than committed to
// android/src/main/assets, because docs/ASSETS.md section 6 keeps upstream art
// out of git and copying it into a source tree would put it right back in.
// A fresh clone therefore builds an APK with no models in it — that is the
// intended outcome, and AndroidLauncher reports it and comes up as a menu.
//
// It is a lot of megabytes — about 36 of converted models, against an APK that
// was 10 without them. Almost all of it is texture: ModelFormat has no shared-
// atlas concept, so each of the eighteen Kenney characters carries its own
// private copy of what is very nearly the same 512x512 image. The demo only
// stands seven of them up, but the choice of WHICH seven lives in DemoModels
// where it belongs, and repeating that list here would be a second place for it
// to drift. Shrinking this is a model-format change, not a build-script one.
//
// Resolved to plain Files at configuration time: the configuration cache
// refuses to serialise a task that closes over a Project or a script object.
val modelAssetsSourceDir = rootProject.layout.projectDirectory.dir("assets/models").asFile
val modelAssetsStagedDir = layout.buildDirectory.dir("generated/openfpsAssets").get().asFile

/**
 * Stages the repository's model tree into the APK's assets.
 *
 * Sync rather than Copy: a model removed from assets/models must disappear from
 * the next APK too, and Copy would leave it behind forever. A missing source
 * directory is not an error — it is the state of every fresh clone — and Sync
 * simply produces an empty staging directory, which is exactly what an APK with
 * no world in it should contain.
 *
 * The `into("models")` sits on the FROM spec rather than on the task, so the
 * task's declared output directory is the assets ROOT and the files land one
 * level down in models/. That is not cosmetic: the source set below registers
 * this task itself as the srcDir, which is what tells Gradle who produces that
 * directory. Registering the plain File instead builds and packages correctly
 * and then fails every task that merely READS the directory — lint, in
 * practice — with "Gradle detected a problem with the following location",
 * because nothing had declared a producer for it.
 */
val stageModelAssets by tasks.registering(Sync::class) {
    group = "openfps"
    description = "Copies assets/models into the APK's assets so the demo has a world."
    from(modelAssetsSourceDir) {
        // Converted models only. assets/models also holds the RAW Kenney
        // downloads the converter reads — .glb, .obj, source textures, licence
        // files — which are ~17 MB the device would carry and never open.
        include("**/*.ofm")
        into("models")
    }
    into(modelAssetsStagedDir)
}

android {
    namespace = "com.openfps.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openfps.android"
        // 23 (Android 6.0) is forced by androidx.room 2.8.x, which declares
        // it as its own floor. libGDX would be happy at 21, but API 21-22 is
        // Android 5.x — a fraction of a percent of active devices — and
        // pinning Room back to reach them would mean carrying an old
        // persistence library forever. Raising the floor is the cheaper side
        // of that trade.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        // :engine is Java 17 and its port contracts use java.util.Optional —
        // I_UserProfilePort.findById returns one. Optional arrived in the
        // Android platform at API 24, so below that it simply does not exist
        // on the device. Desugaring backports it (and streams, java.time,
        // java.util.function) into the APK.
        //
        // This is not optional in any real sense: without it, minSdk would
        // have to be 24+ AND every future engine API using a modern JDK type
        // would be a new compatibility decision. With it, :engine stays free
        // to be ordinary Java 17.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            // No shrinking yet. R8 strips libGDX reflection targets without a
            // keep rule set, and there is nothing to ship yet that would
            // justify debugging that now.
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            // The staged models, in addition to the default
            // src/main/assets — not instead of it, so anything genuinely
            // committed as an asset later still arrives.
            //
            // The TASK, not its output path. srcDir resolves it through
            // Project.files(), which carries the task dependency with it, so
            // every consumer of the assets — merge, package, AND lint — knows
            // what produces them. See stageModelAssets.
            assets.srcDir(stageModelAssets)
        }
    }

    // libGDX ships one .so per ABI inside gdx-platform. Nothing here needs
    // a jniLibs source set — the natives arrive as a dependency.
    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }

    testOptions {
        unitTests {
            // The android.jar on a unit test's classpath is a stub: every
            // method body is `throw new RuntimeException("Stub!")`. Platform
            // adapters here log through android.util.Log on paths that are
            // otherwise plain Java — AndroidWindowPort.create() and
            // AndroidAdapterFactory.init() do nothing else — so without this
            // flag those methods cannot be called at all, and the module's
            // pure logic would be untestable for the sake of a log line.
            //
            // With it, stubbed platform calls return 0/false/null instead of
            // throwing. That is a licence to test logic that merely PASSES
            // THROUGH the framework, never to assert on framework behaviour:
            // a stub that returns false is not evidence about what Android
            // does. Anything that needs real framework behaviour belongs in
            // androidTest, on a device.
            isReturnDefaultValues = true

            all {
                it.useJUnitPlatform()
                it.testLogging {
                    events("passed", "skipped", "failed")
                    showExceptions = true
                    showCauses = true
                }
            }
        }
    }
}

// The `natives` configuration is not something AGP provides; libGDX's own
// templates declare it and then unpack the classified jars into jniLibs.
// It must be created BEFORE the dependencies block that populates it, and
// referenced there by string — a configuration made at script runtime gets
// no type-safe accessor generated for it.
val natives: Configuration by configurations.creating

dependencies {
    // Excluded at the consumer rather than removed from :engine, so the
    // engine stays self-sufficient for headless runs and its own tests.
    //
    //   logback-classic  — does not work on Android at all. It is a pure
    //       transitive: nothing in the tree imports ch.qos.logback, only
    //       the SLF4J facade (STYLE.md § 8). Android platform code logs
    //       through android.util.Log.
    //   sqlite-jdbc      — bundles precompiled natives for ~20 desktop
    //       triplets. Android will use Room via its own I_UserProfilePort
    //       adapter; HalBackend.SQLITE is never selected here.
    //
    // Together these were ~4 MB of an APK that draws a menu.
    implementation(project(":engine")) {
        exclude(group = "ch.qos.logback")
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }

    // The framebuffer presenter, the UI state machine and the input
    // accumulator — the libGDX code that is not platform-specific, shared with
    // :desktop rather than written twice. It exposes :engine as `api`, so the
    // same two exclusions have to be repeated here: an exclusion applies to the
    // dependency it is declared on, and logback arriving transitively through
    // this edge would be just as broken on Android as arriving directly.
    implementation(project(":gdxshared")) {
        exclude(group = "ch.qos.logback")
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }

    // SLF4J -> android.util.Log. This is the replacement for the logback
    // excluded above, NOT an extra: SLF4J 2.x with no provider on the
    // classpath binds to the NOP logger and silently discards every engine
    // log line — including LOG.error from worker dispatch failures. The
    // failure mode is an app that looks fine and reports nothing, which is
    // the worst possible one to debug an emulator bring-up against.
    implementation("uk.uuid.slf4j:slf4j-android:2.0.17-0")

    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

    // Room — the Android side of I_UserProfilePort. This is the counterpart
    // to sqlite-jdbc on desktop, and the reason that one is excluded above:
    // each module carries the persistence engine its platform can actually
    // load. The port keeps the engine unaware of either.
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")

    // Backports java.util.Optional, streams, java.time and friends so the
    // Java 17 engine runs below API 24. Paired with
    // isCoreLibraryDesugaringEnabled above; neither works alone.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Per-ABI natives. Unlike desktop, Android needs each architecture
    // listed explicitly; AGP packages them into the APK per split.
    "natives"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    "natives"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    "natives"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    "natives"("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")

    // Same versions as :engine, :desktop and :tools — one JUnit line and one
    // AssertJ line across the whole build. Local unit tests only; no
    // Robolectric and no androidTest dependencies, because everything covered
    // here runs on a plain JVM and everything that would not is deliberately
    // left uncovered rather than faked. See testOptions above.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Unpacks each natives-<abi> jar into the ABI folder AGP expects. Without
// this the .so files sit unreachable inside jars on the classpath and the
// app dies at startup with UnsatisfiedLinkError.
//
// Written as a typed task with injected FileSystemOperations and
// ArchiveOperations rather than a doLast using project.copy { }/zipTree().
// Those two resolve against the Project at EXECUTION time, which the
// configuration cache forbids — the symptom is an opaque
// "Configuration cache state could not be cached" failure pointing at an
// unrelated AGP task, not at this one.
abstract class CopyAndroidNativesTask : DefaultTask() {

    @get:InputFiles
    abstract val nativeJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        nativeJars.files.forEach { jar ->
            val abi = jar.name.substringAfterLast("natives-").substringBeforeLast(".jar")
            fs.copy {
                from(archives.zipTree(jar))
                into(outputDir.dir(abi))
                include("*.so")
            }
        }
    }
}

tasks.register<CopyAndroidNativesTask>("copyAndroidNatives") {
    group = "openfps"
    description = "Unpacks libGDX per-ABI native libraries into jniLibs."
    nativeJars.from(natives)
    outputDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn("copyAndroidNatives")
}

