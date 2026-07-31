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

OpenFPS is a five-module Gradle build. Knowing which is which is the difference
between `run` doing what you meant and `run` opening a window you didn't want.

| Module | Included when | What it is |
|---|---|---|
| `:engine` | always | The engine. Platform-free — builds and tests headless with no display |
| `:gdxshared` | always | libGDX **core** only: framebuffer presentation, the welcome screen, the UI state machine. Shared by both launchers |
| `:desktop` | always | libGDX LWJGL3 window, input, `DesktopLauncher` |
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

## The two run scripts — start here

Two PowerShell scripts at the repository root are the intended way to run the
game on either platform. **Prefer them over the raw Gradle commands below.**

```powershell
.\run-desktop.ps1        # rebuild, verify, open a window
.\run-android.ps1        # rebuild, boot the emulator, install, launch, tail logcat
```

They exist because of one specific, repeated failure: **reporting a feature as
"not there" while running a build that predates it.** Both scripts recompile on
every invocation, print the commit and the working-tree state *before* anything
launches, and stop with a banner if the build fails rather than falling back to
the binary that is already there. If the window you are looking at came from one
of these scripts, the console above it says exactly which commit it is.

From VS Code, both are one entry in **Ctrl+Shift+P → Tasks: Run Task** — see
[VS Code tasks](#vs-code-tasks).

### `run-desktop.ps1`

| Option | Effect |
|---|---|
| `-RenderMode 480p\|720p\|native` | Internal render resolution. Confirm it on the overlay's `RES` line |
| `-RenderFilter linear\|nearest` | How the finished frame is blitted up to the window |
| `-DebugOverlay` | Start with `FPS` / `FRAME` / `RENDER` / `RES` on screen |
| `-StartInGame` | Skip the menu, open straight into the world |
| `-Fps 30\|60\|120` | Simulation tic rate |
| `-FpsLog <seconds>` | `FramebufferPresenter`'s three-rate log — platform, presented, rendered |
| `-Workers <n>` | Pin the render worker pool instead of sizing it from the CPU count |
| `-Assets <dir>` / `-Model <file>.ofm` | Model root, or a single model on the orbit camera |
| `-Net <id>:<port>` / `-Peer <id>@<host>:<port>` | Multiplayer, as in *Two peers on one machine* below |
| `-Screenshot <file>.png` / `-ScreenshotFrame <n>` | Capture that frame and exit instead of waiting to be closed |
| `-Clean` | Delete compiled output first, so the recompile is from scratch |
| `-NoLaunch` | Build and report, then stop |
| `--help` | The full list, with the reasoning. `-h` and `-Help` also work |

```powershell
# The one worth memorising: straight into the world, diagnostics visible.
.\run-desktop.ps1 -StartInGame -DebugOverlay

# Unattended capture at the window's own resolution.
.\run-desktop.ps1 -StartInGame -DebugOverlay -RenderMode native -Screenshot C:\tmp\native.png
```

> **Why these options and not others.** Every `-D` the script passes is a
> property that `desktop/build.gradle.kts` explicitly forwards into the forked
> JVM. A `-D` on a Gradle command line otherwise lands on the **daemon** and
> never reaches the game — it would look accepted and do nothing, which is
> precisely the class of bug these scripts exist to remove. Add an option here
> only after adding the property to that forwarding list.

If `assets/models` is empty the script says so in words, names
`:tools:regenerateDemoAssets`, and stops — rather than letting `DesktopLauncher`
exit **3** and Gradle report it as `finished with non-zero exit value 3`.

### `run-android.ps1`

| Option | Effect |
|---|---|
| `-RenderMode 480p\|720p\|native` | Internal render resolution, as an Intent extra |
| `-DebugOverlay` | Start with the diagnostic overlay on, as an Intent extra |
| `-Avd <name>` | Which AVD to boot. Default `OpenFPS_API36` |
| `-Window` | Show the emulator window. Headless by default |
| `-GpuMode <mode>` | Emulator `-gpu`. Default `swiftshader_indirect` |
| `-NoEmulator` | Require an already-connected device — use this for a physical phone |
| `-BootTimeoutSeconds <n>` | How long to wait for `sys.boot_completed`. Default 300 |
| `-Screenshot <file>.png` / `-ScreenshotDelaySeconds <n>` | Capture and exit |
| `-Clean` / `-NoLaunch` / `-NoLogcat` | As on desktop; `-NoLogcat` skips the log tail |
| `--help` | The full list |

The script reuses a running emulator or device if there is one and boots the AVD
otherwise, waiting on `sys.boot_completed` rather than on `adb devices` — the
latter answers as soon as `adbd` is up, which is well before `am start` can
work. It then force-stops the app, installs with `-r`, launches, and tails
`adb logcat --pid=<pid>`.

> **`-gpu host` is not the default and must not be.** On this machine's Intel
> iGPU it dies with `Failed to find ColorBuffer`, and the crash-consent dialog it
> leaves behind blocks the *next* boot until someone dismisses it by hand.
> `swiftshader_indirect` is slower per frame and always comes up.

**Options reach an APK through Intent extras, not `-D`.** An APK has no command
line. `AndroidLauncher.LAUNCH_PROPERTIES` lists the properties an
`am start --es <name> <value>` may seed, and copies them into system properties
before the objects that read them are constructed. Each one is echoed in logcat
as `Launch extra: <name>=<value>`, so there is no version of "it was ignored"
that leaves no trace. Only listed names are honoured — a property name is a
global, and an Activity that copied arbitrary extras into one would let anything
that can start it set anything at all.

**`-StartInGame` is desktop-only, and the script refuses it rather than ignoring
it.** On desktop the flag sets `openfps.startInGame`, which
`GdxFrameLoopListener` reads in its constructor. Android has no equivalent seam:
entering the world fires the match gate, and the match gate is also what
preloads the weapon sound — which needs a live `Gdx.audio` that does not exist
until the frame loop has started. Tap **Single Player**.

`-Screenshot` photographs the **menu**, because nothing unattended can tap that
button and a hardcoded tap coordinate would rot the first time the menu is laid
out differently. The script prints the two `adb` commands that get you into the
world and take a second shot; on a 2400x1080 landscape panel the first menu
button is at `input tap 1200 358`.

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
3. Compiles and runs all JUnit 5 tests — **2339** of them (1625 `:engine`, 300 `:gdxshared`, 177 `:android` when an SDK is present, 164 `:desktop`, 73 `:tools`). `:android` is counted **once**: its suite runs against a debug and a release variant, so the raw XML tally reports 354
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

> `:desktop:run` compiles first, so it is not itself a stale-build risk — but it
> reports nothing about what it built. `.\run-desktop.ps1` wraps it with the
> commit, the build result and the option pass-through; prefer that.

| Flag | Effect | Honoured by |
|---|---|---|
| `--fps=30\|60\|120` | Tic rate. Anything else is rejected at startup. Default 60 | both |
| `--no-sqlite` | Use the in-memory profile port instead of on-disk SQLite | `:engine:run` only |
| `--headless` | Force the null adapter factory (implies `--no-sqlite`) | `:engine:run` only |
| `--assets=<dir>` | Model root. Default `assets/models` | `:desktop:run` only |
| `--start-in-game` | Skip the menu and open straight into the world | `:desktop:run` only |
| `--net=<id>:<port>` | Be player `<id>`, listen on UDP `<port>`. `0` asks the OS for a free port | `:desktop:run` only |
| `--peer=<id>@<host>:<port>` | Connect to a peer. May be given more than once | `:desktop:run` only |

`:desktop:run` hard-selects the desktop HAL backend, so `--no-sqlite` and
`--headless` do nothing there — a windowed launcher cannot be headless.

### Two peers on one machine

Neither half of `--net` has a safe default and both are required. Two peers
sharing a player id would each read the other's packets as coming from
themselves and drop them silently — the game would open, connect, and show
nobody. A default port cannot work for two instances on one machine, which is
the first thing anyone tries.

```powershell
# terminal 1
.\gradlew :desktop:run --args="--start-in-game --net=1:5021 --peer=2@127.0.0.1:5022"

# terminal 2
.\gradlew :desktop:run --args="--start-in-game --net=2:5022 --peer=1@127.0.0.1:5021"
```

Each process logs a `Network summary` line on exit — packets and bytes both
ways, commands accepted, and counts of malformed packets and packets from
unknown senders. A measured run of the above exchanged 77 KB with both of the
last two at zero.

**What this does and does not do.** The transport carries inputs both ways and
keeps the acknowledgement and loss state a lockstep simulation needs. It does
**not** yet turn a remote player into a body you can see and shoot. A session
that exchanges packets perfectly and shows nobody looks exactly like a broken
one, so check the summary line rather than the screen.

---

## A native Windows launcher

`gradlew :desktop:run` is a JVM, so Task Manager lists `java.exe` whatever the
window title says. `jpackage` is what changes that:

```powershell
.\gradlew :desktop:packageWindows
.\desktop\build\dist\OpenFPS\OpenFPS.exe
```

The result is an app-image — a directory holding `OpenFPS.exe`, the classes and
a trimmed runtime — rather than an installer, which would need WiX on `PATH`.
The `.ico` is generated at build time from `WindowIcon`'s design, so there is no
icon file in the repository and none to keep in step.

The **window** icon needs none of this: it is generated in code and applied
through GLFW every run, including under `:desktop:run`.

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

## Reading the docs as a website

There is a lot of Markdown in this repository — a root set, a `docs/` set, and a
`README.md` beside almost every package. Reading it on GitHub means a lot of
tab-hopping.

```
gradlew :tools:buildDocsSite
```

Renders every one of them into `docs/site/` as a static site with a sidebar, a
per-page table of contents, working cross-links, and a dark mode. No CDN, no
fonts, no JavaScript framework — one stylesheet and about forty lines of vanilla
JS for the sidebar. Open `docs/site/index.html`.

The generator is `:tools`, so it ships nothing. It **fails the build** if any
cross-document link fails to resolve, which is the main thing that rots in a doc
set this size. `-PdocsOut=<dir>` writes somewhere else.

The Markdown files remain the source of truth. Edit those; regenerate after.

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

> `.\run-android.ps1` does everything in this section in one command, and waits
> for the right things — see [The two run scripts](#the-two-run-scripts--start-here).
> What follows is the same work by hand, for when you need one step of it.

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

### It is playable

Tap **Single Player** and you are in the same room the desktop build shows,
drawn by the same software rasterizer, with the same seven bots shooting back.

| Control | Where |
|---|---|
| Move | anywhere on the left half — the stick appears under your thumb |
| Look | anywhere on the right half — drag to turn |
| Fire | the large disc, bottom right |
| Jump | the smaller disc, inboard of fire |
| Leave the match | the disc top right, or the system back key |

There is no sprint on this platform, deliberately — see `AndroidBindings`.

### The APK needs the models staged first

`stageModelAssets` copies `assets/models/**/*.ofm` into the APK. That directory
is gitignored, so **a fresh clone builds an APK with no world in it**: the menu
comes up, Single Player enters an empty room, and logcat says so at `ERROR`
naming the regenerate command. Produce the models first — see *Demo assets*
above — then rebuild.

With the models staged the debug APK is about 11 MB; without them, about 7.

### Reading engine logs

`slf4j-android` tags by logger name and Android truncates tags to 23
characters, so `com.openfps.engine.demo.DemoGameplayPort` appears as
`coed.DemoGameplayPort`. That looks like corruption and is not. `-s OpenFPS:V`
filters to the platform classes only; drop it to see the engine.


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

### VS Code tasks

`.vscode/tasks.json` **is committed** — it is the one file in that directory
that is project configuration rather than personal state, and `.gitignore`
un-ignores it by name. `settings.json` and `launch.json` stay ignored: the first
holds absolute JDK paths valid on exactly one machine, and the second is a
per-developer debugger set.

**Ctrl+Shift+P → Tasks: Run Task**:

| Task | What it runs |
|---|---|
| `run: desktop` | `run-desktop.ps1` — rebuild and play |
| `run: desktop (in game, debug overlay)` | `-StartInGame -DebugOverlay` — the fastest way to confirm a running build is the one you just built |
| `run: desktop (pick render mode)` | Prompts for 480p / 720p / native |
| `run: desktop (build only)` | `-NoLaunch` — "did that compile?" without a window |
| `run: android` | `run-android.ps1` — build, boot, install, launch, tail logcat |
| `run: android (debug overlay)` | The same with the overlay on |
| `run: android (pick render mode)` | Prompts for the mode |
| `run: android (emulator window visible)` | `-Window`, still on swiftshader |
| `gradle: build`, `gradle: test (...)`, `gradle: checkstyleMain`, `gradle: :desktop:run`, `gradle: :android:assembleDebug` | The plain Gradle set, unchanged |

The run tasks invoke `powershell.exe` with `-ExecutionPolicy Bypass -File`
rather than calling `.\run-desktop.ps1` as a shell command. A
default-configured Windows refuses to run an unsigned `.ps1` at all, and the
error it produces names execution policy rather than the task — which would turn
a one-click launch into a support question on a fresh machine.

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
