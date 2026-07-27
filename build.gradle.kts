// Imported rather than fully qualified: the `java { }` extension accessor added
// by the java plugins shadows the `java` package name inside a Gradle Kotlin
// DSL script, so `java.net.http.HttpClient` does not resolve.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

// Root project. Deliberately applies NO Java plugin — the modules do that.
// Everything here is cross-module: shared coordinates and the asset payload.
//
//   :engine   pure Java 17 core, no platform dependencies
//   :desktop  libGDX LWJGL3 backend — Windows/Linux/macOS window and input
//   :android  libGDX Android backend — Activity, GLSurfaceView, APK
//
// The split exists because the Android Gradle Plugin needs its own module; it
// cannot coexist with java-library in a single build script. :engine staying
// platform-free is what keeps it testable headlessly on any machine.

allprojects {
    group = "com.openfps"
    version = "0.1.0-SNAPSHOT"
}

// ---------------------------------------------------------------------------
// Build-time tooling must never reach a shipped runtime classpath
// ---------------------------------------------------------------------------
// :tools holds GltfConverter and, with it, a JSON library. docs/ASSETS.md § 4
// permits that dependency for exactly one reason: build-time tooling ships
// nothing. That permission is only worth having if it stays true, and "it
// stays true because nobody added a dependency" is not a guarantee — it is a
// hope with a review step in front of it.
//
// So it is checked. This configuration resolves the complete transitive
// runtime graph of everything the project actually distributes, and the task
// below fails the build if a build-time artifact appears anywhere in it.
// Adding `implementation(project(":tools"))` to :engine or :desktop turns a
// silent architectural regression into a named, immediate build failure.

// The root project declares repositories solely so the configuration below can
// resolve. It still applies no plugins and produces no artifact of its own.
repositories {
    mavenCentral()
}

val shippedRuntimeClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR)
        )
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

dependencies {
    // :android is guarded behind an SDK check in settings.gradle.kts, so it is
    // not listed here. It depends on :engine and never on :tools; the same
    // one-way edge protects it.
    shippedRuntimeClasspath(project(":engine"))
    shippedRuntimeClasspath(project(":desktop"))
}

tasks.register("verifyToolsIsolation") {
    group = "verification"
    description = "Fails if build-time asset tooling reaches a shipped runtime classpath."

    // Artifact name prefixes that belong to build time only, matched against
    // jar file names — which carry the module or dependency name before the
    // version. Held as a local so the task action captures a plain list rather
    // than a reference to this script, which the configuration cache rejects.
    val forbidden = listOf("tools-", "gson-")

    // The Provider resolves at execution time, keeping configuration cheap.
    val shipped = shippedRuntimeClasspath.elements
    inputs.files(shippedRuntimeClasspath)

    doLast {
        val names = shipped.get().map { it.asFile.name }.sorted()
        val offenders = names.filter { name -> forbidden.any(name::startsWith) }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Build-time tooling reached a shipped runtime classpath: $offenders\n" +
                "  :tools and its dependencies are build-time only — docs/ASSETS.md § 4.\n" +
                "  Remove the dependency on :tools from :engine / :desktop / :android."
            )
        }
        logger.lifecycle(
            "Runtime classpath clean: ${names.size} artifacts, no build-time tooling."
        )
    }
}

// ---------------------------------------------------------------------------
// Game data
// ---------------------------------------------------------------------------
// Assets are never committed to git — see docs/ASSETS.md for the licensing
// policy, approved sources, and size budget.
//
// Upstream art (Kenney, Quaternius, KayKit, Poly Haven, ambientCG) is all CC0,
// which explicitly permits redistribution. So rather than downloading from a
// half-dozen third-party sites at build time — unpinnable URLs, uptime we do
// not control — we mirror the curated payload into a single versioned release
// on this repository. One URL, one checksum, reproducible forever.
//
// Deliberately NOT wired into `build`: assets are opt-in and CI must stay
// hermetic. It is also not yet wired into `run` / `distZip` / `distTar`,
// because there is no asset payload and no loader to consume one — adding the
// dependency now would break the working headless smoke test for everyone.
// Wire it into those tasks when the Phase 2 loader lands.
val assetsVersion = "v1"
val assetsArchive = "openfps-assets-$assetsVersion.zip"
val assetsUrl =
    "https://github.com/nicholashutter/OpenFPS/releases/download/assets-$assetsVersion/$assetsArchive"

// Placeholder. The assets-v1 release has not been published yet; until it is,
// fetchAssets fails with an actionable message rather than downloading
// something unverifiable. See docs/ASSETS.md § 9.
val assetsSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

tasks.register("fetchAssets") {
    group = "openfps"
    description = "Downloads the pinned game-data payload and verifies its SHA-256."

    // Captured at configuration time so the task action holds no project
    // reference — required for the configuration cache, which is enabled in
    // gradle.properties.
    val url = assetsUrl
    val expected = assetsSha256
    val cacheDir = layout.projectDirectory.dir("assets/cache").asFile
    val target = File(cacheDir, assetsArchive)

    outputs.file(target)

    doLast {
        if (expected.none { it != '0' })
        {
            throw GradleException(
                "Game-data release 'assets-$assetsVersion' has not been published yet.\n" +
                "  Publish the curated payload as a GitHub release, then set\n" +
                "  assetsSha256 in build.gradle.kts to its real digest.\n" +
                "  See docs/ASSETS.md section 9 (Open items)."
            )
        }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        // Digest, not timestamp, is the correctness criterion. A cache that
        // already hashes correctly needs no network access at all, so this is
        // both incremental and usable offline.
        if (target.exists() && sha256Of(target).equals(expected, ignoreCase = true)) {
            logger.lifecycle("Game data up to date: ${target.name}")
            return@doLast
        }

        cacheDir.mkdirs()
        logger.lifecycle("Fetching game data from $url")

        val partial = File(cacheDir, "$assetsArchive.part")
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))

        if (response.statusCode() != 200) {
            partial.delete()
            throw GradleException(
                "Game-data download failed: HTTP ${response.statusCode()} from $url"
            )
        }

        // A mismatch here means the payload was corrupted in transit or the
        // release was re-tagged with different content. Both need a human;
        // neither is safe to shrug off, so this fails the build loudly and
        // leaves nothing behind that a later run could mistake for valid.
        val actual = sha256Of(partial)
        if (!actual.equals(expected, ignoreCase = true)) {
            partial.delete()
            throw GradleException(
                "Game-data checksum mismatch — refusing this payload.\n" +
                "  expected: $expected\n" +
                "  actual:   $actual\n" +
                "  Either the download was corrupted or the release was re-tagged."
            )
        }

        partial.copyTo(target, overwrite = true)
        partial.delete()
        logger.lifecycle("Game data verified: ${target.name} (${target.length()} bytes)")
    }
}
