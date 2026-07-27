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

android {
    namespace = "com.openfps.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openfps.android"
        // 21 covers ~99% of active devices and is libGDX's practical floor
        // for OpenGL ES 3.0.
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(project(":engine"))

    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

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
