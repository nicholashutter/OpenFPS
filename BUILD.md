# BUILD.md — OpenFPS Build Reference

> How to build, test, and run OpenFPS on all supported platforms.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java JDK | 17 LTS or newer | Required. Use Eclipse Temurin / Adoptium. `java -version` must show 17+ — a JDK 8 `JAVA_HOME` will fail to start the Gradle daemon |
| Gradle | 8.13 | Bundled via wrapper — no install needed. **Do not downgrade**, see [Gradle wrapper](#gradle-wrapper) |
| Android SDK | **36** | Optional, `:android` only. SDK 34 will *not* build — androidx.core 1.17.0 forces compileSdk 36 |
| Git | Any recent | For version control |

### Installing the JDK (if needed)

The project **targets Java 17** (`sourceCompatibility`/`targetCompatibility`),
so it always emits Java 17 bytecode — but it **builds on any JDK 17 or newer**.
JDK 21 LTS is a fine and recommended choice; building on 21 still produces
class file version 61 (Java 17).

**Windows (Chocolatey):**
```powershell
choco install temurin21 -y
```

**macOS (Homebrew):**
```bash
brew install openjdk@21
```

**Linux (apt):**
```bash
sudo apt install openjdk-21-jdk
```

After installing, point `JAVA_HOME` at it (adjust the version folder to match
what you actually have):
```powershell
# Current shell only
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"

# Persist for your user (new shells pick it up)
[Environment]::SetEnvironmentVariable("JAVA_HOME",
    "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot", "User")
```

> **`Unrecognized VM option 'UseZGC'` / `Could not create the Java Virtual
> Machine`** means `JAVA_HOME` is pointing at an old JDK (typically a leftover
> JDK 8). `gradle.properties` asks for ZGC, which needs a modern JVM.
>
> On Windows, `JAVA_HOME` can be set at **both** User and Machine scope. User
> scope wins for newly launched processes — but a running IDE keeps the
> environment it started with, so **restart your IDE/terminal** after changing
> it. To inspect both scopes:
> ```powershell
> [Environment]::GetEnvironmentVariable("JAVA_HOME","User")
> [Environment]::GetEnvironmentVariable("JAVA_HOME","Machine")
> ```
> Clearing a stale Machine-scope value needs an **elevated** shell:
> ```powershell
> [Environment]::SetEnvironmentVariable("JAVA_HOME", $null, "Machine")
> ```

---

## The modules

OpenFPS is a four-module Gradle build. Knowing which is which is the difference
between `run` doing what you meant and `run` opening a window you didn't want.

| Module | Included when | What it is |
|---|---|---|
| `:engine` | always | The engine. Platform-free — builds and tests headless with no display |
| `:desktop` | always | libGDX LWJGL3 window, input, presentation, `DesktopLauncher` |
| `:tools` | always | Build-time only: glTF conversion and headless render previews |
| `:android` | **only when an Android SDK is present** | libGDX Android backend |

> **The `:android` trap.** `settings.gradle.kts` includes `:android` only if
> `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties` is present. Without
> one, the module is **silently skipped** and `gradlew build` exits 0 having
> compiled no Android code at all. Worse, the configuration cache is on by
> default, so the "No Android SDK found — skipping :android" notice is not even
> printed on a cached run. **A green build is not evidence that `:android`
> compiles.** Set `ANDROID_HOME` and check for `:android:` tasks in the output.

---

## Standard Build (Desktop — Windows/Linux/macOS)

```powershell
# From the project root:
.\gradlew build

# Build without tests (faster iteration):
.\gradlew build -x test

# Clean + rebuild:
.\gradlew clean build
```

### What `build` Does

1. Compiles all Java source across `:engine`, `:desktop` and `:tools`
2. Runs Checkstyle over main *and* test sources (`maxWarnings = 0` — one warning fails the build)
3. Compiles and runs all JUnit 5 tests — **1064** of them (828 `:engine`, 99 `:desktop`, 73 `:tools`, 64 `:android` when an SDK is present)
4. Assembles JAR artifacts
5. Runs `verifyToolsIsolation`, which fails the build if `:tools` or its Gson
   dependency ever reaches a shipped runtime classpath
6. Generates Checkstyle and test HTML reports **per module** (see paths below)

### Run the Engine

Both `:engine` and `:desktop` apply the `application` plugin, so **bare
`gradlew run` matches both tasks and launches both** — a headless smoke test
*and* a GLFW window. Always name the module.

```powershell
# Headless smoke test: boots the full stack, runs rate x 2 tics (~2s), shuts down
.\gradlew :engine:run
.\gradlew :engine:run --args="--fps=30"
.\gradlew :engine:run --args="--no-sqlite"
.\gradlew :engine:run --args="--headless"

# The playable demo, in a window (needs art — see below)
.\gradlew :desktop:run
```

| Flag | Effect | Honoured by |
|---|---|---|
| `--fps=30\|60\|120` | Tic rate. Anything else is rejected at startup. Default 60 | both |
| `--no-sqlite` | Use the in-memory profile port instead of on-disk SQLite | `:engine:run` only |
| `--headless` | Force the null adapter factory (implies `--no-sqlite`) | `:engine:run` only |
| `--assets=<dir>` | Model root. Default `assets/models` | `:desktop:run` only |

`:desktop:run` hard-selects the desktop HAL backend, so `--no-sqlite` and
`--headless` do nothing there — a windowed launcher cannot be headless.

With SQLite enabled the engine creates or loads a profile at
`~/.openfps/profile.db` and accumulates playtime across runs; override the path
with the `OPENFPS_PROFILE_DB` environment variable.

---

## Demo assets — required before `:desktop:run` will show anything

**Demo art is not in the repository.** `assets/gltf/` and `assets/models/` are
gitignored; the repo records provenance, licences and SHA-256s in
`docs/DEMO_ASSETS.md`, not the art itself. A fresh clone has no models, and
`:desktop:run` will report that and exit with code **3** rather than open an
empty window.

```powershell
# 1. Download and unzip the two CC0 Kenney packs listed in docs/DEMO_ASSETS.md,
#    then convert them to the runtime .ofm format:
.\gradlew :tools:regenerateDemoAssets -PkenneyRaw=C:\path\to\unzipped\packs

# 2. Run the demo
.\gradlew :desktop:run
```

Without `-PkenneyRaw` the task still succeeds, but staging is skipped and you
get **only a 60-triangle greybox room and no weapon** — the deliberate fallback
for when no pack has been staged. It is a room to stand in, not the demo.

| Task | Effect |
|---|---|
| `:tools:regenerateDemoAssets -PkenneyRaw=<dir>` | Stage the packs, convert everything, verify budgets |
| `:tools:regenerateDemoAssets` | No staging; emits the generated fallback room only |
| `:tools:verifyModels` | Re-check an existing `assets/models` without reconverting |
| `:tools:demoPreview -PdemoOut=<dir>` | Render the four demo shots to PNGs, headless |
| `:tools:demoPreview -PdemoOut=<dir> -PdemoThreads=8 -PdemoFrames=300` | Same, plus p50/p90/p99 frame timing |
| `:tools:renderPreview "--args=--model=<f>.ofm --out=<f>.png"` | Render a single model |

Point `-PdemoOut` **outside** the repository — `docs/ASSETS.md` § 6 keeps
generated art out of git, and the task refuses to run without it.

> The root `fetchAssets` task — which would download a pinned, SHA-256-verified
> asset payload and remove the manual step above — **fails on purpose today**.
> The `assets-v1` release has not been published, so its digest is still a
> placeholder and the task refuses to download something it cannot verify. See
> `docs/ASSETS.md` § 9. Until that release exists, the manual path above is the
> only one.

---

## Checkstyle Only

```powershell
.\gradlew checkstyleMain
```

HTML reports are **per module**:
`engine\build\reports\checkstyle\main.html`,
`desktop\build\reports\checkstyle\main.html`,
`tools\build\reports\checkstyle\main.html`.

(A stale `build/reports/` directory may still exist at the repo root from before
the module split. Ignore it — nothing writes there any more.)

---

## Tests Only

```powershell
.\gradlew test                 # all modules
.\gradlew :engine:test         # one module
```

HTML reports, again per module: `engine\build\reports\tests\test\index.html`.

---

## Android Build

Requires **Android SDK 36** and `ANDROID_HOME`. Gradle must run on JDK 17+
(21 recommended).

```powershell
$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"

# Build the APK
.\gradlew :android:assembleDebug

# Install and launch on a connected device or running emulator
adb install -r android\build\outputs\apk\debug\android-debug.apk
adb shell am start -n com.openfps.android/com.openfps.android.AndroidLauncher

# Engine logs
adb logcat -s OpenFPS:V
```

`adb` ships in `<SDK>\platform-tools`, which is not on `PATH` by default.

`:android` is a real **application** module (`com.android.application`,
namespace `com.openfps.android`) whose launcher activity is `AndroidLauncher` —
not a library. Plain `.\gradlew build` builds it too, provided the SDK is
visible. There is no `-Pandroid` flag; no build script reads that property.

> **Screenshots:** use `adb shell screencap -p /sdcard/x.png` followed by
> `adb pull`. PowerShell's `>` redirection corrupts `adb exec-out screencap -p`
> output by injecting a BOM and mangling CRLF.

---

## Gradle wrapper

The wrapper is **pinned at 8.13 and must not be downgraded**. The version chain
is forced, not chosen:

```
gdx-backend-android 1.14.2 → androidx.core 1.17.0 → compileSdk 36, AGP >= 8.9.1
AGP 8.13.x → Gradle >= 8.13
```

Running `gradlew wrapper --gradle-version=8.10` drops below the AGP floor and
breaks `:android`. If the wrapper JAR is genuinely missing, restore it from git
rather than regenerating at a lower version.

---

## IntelliJ IDEA

1. Open the project root as a Gradle project
2. Set Project SDK to your installed JDK (17 or newer — e.g. 21), and set
   "Project language level" to **17** to match `targetCompatibility`
3. Enable "Import Gradle annotations"
4. Enable "Annotation processing"
5. Enable Checkstyle plugin and point it to `config/checkstyle/checkstyle.xml`

---

## VS Code

1. Install Extension Pack for Java (Microsoft)
2. Install Checkstyle for Java
3. Open the project folder
4. Set `java.configuration.runtimes` in `.vscode/settings.json`:

```json
{
    "java.configuration.runtimes": [
        {
            "name": "JavaSE-21",
            "path": "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot",
            "default": true
        }
    ],
    "checkstyle.configuration": {
        "location": "project",
        "properties": {
            "checkstyle.config": "config/checkstyle/checkstyle.xml"
        }
    }
}
```

---

## Troubleshooting

### `Could not find toolchain`

`gradle.properties` already enables toolchain auto-detection, so this is rarely
the real problem — check `JAVA_HOME` first. If you do pass the flag explicitly,
**quote it**, or PowerShell 5.1 splits the argument and Gradle reports a
nonexistent task name:

```powershell
.\gradlew build "-Dorg.gradle.java.installations.auto-detect=true"
```

### Checkstyle errors

```powershell
.\gradlew checkstyleMain 2>&1 | Select-String "error"
```

### `verifyToolsIsolation` failed

Something added a dependency edge from a shipped module to `:tools`, or pulled
Gson onto a runtime classpath. Both are build-breaking by design — see the task
in the root `build.gradle.kts`.

### Build is slow

`gradle.properties` already turns on the parallel executor, the build cache and
the configuration cache, so `--parallel --build-cache` adds nothing. If a build
is unexpectedly slow, suspect the configuration cache being invalidated — it
prints the reason.

---

## CI/CD

**There is no CI yet.** `.github/workflows/` does not exist. When it is set up,
the workflow should:
- Run `gradlew build` on push to `main` and all PRs
- Set `ANDROID_HOME` and build `:android:assembleDebug` explicitly, since a
  build without the SDK skips the module silently and proves nothing
- Upload Checkstyle HTML reports (per module) as artifacts
- Upload test results

---
