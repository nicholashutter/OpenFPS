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

    // libGDX ships one .so per ABI inside gdx-platform. Nothing here needs
    // a jniLibs source set — the natives arrive as a dependency.
    packaging {
        resources {
            excludes += "META-INF/*"
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
