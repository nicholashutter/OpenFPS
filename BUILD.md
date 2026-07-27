# BUILD.md — OpenFPS Build Reference

> How to build, test, and run OpenFPS on all supported platforms.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java JDK | 17 LTS | Required. Use Eclipse Temurin / Adoptium. `java -version` must show 17 or higher — a JDK 8 `JAVA_HOME` will fail to start the Gradle daemon |
| Gradle | 8.x | Bundled via wrapper — no install needed |
| Android SDK | 34+ | Optional. Only for `-Pandroid` profile |
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

## Standard Build (Desktop — Windows/Linux)

```powershell
# From the project root:
.\gradlew build

# Build without tests (faster iteration):
.\gradlew build -x test

# Clean + rebuild:
.\gradlew clean build
```

### What `build` Does

1. Compiles all Java source (`src/main/java`)
2. Runs Checkstyle style checks
3. Compiles and runs all JUnit 5 tests
4. Assembles JAR artifacts
5. Generates Checkstyle HTML reports in `build/reports/checkstyle/`

### Run the Engine

```powershell
.\gradlew run                                  # 60 Hz, SQLite profile, ~2s
.\gradlew run --args="--fps=30"                # 30 or 120 Hz
.\gradlew run --args="--no-sqlite"             # in-memory profile instead of SQLite
.\gradlew run --args="--headless"              # null adapters throughout
```

| Flag | Effect |
|---|---|
| `--fps=30\|60\|120` | Tic rate. Anything else is rejected at startup. Default 60. |
| `--no-sqlite` | Use the in-memory profile port instead of on-disk SQLite. |
| `--headless` | Force the null adapter factory (implies `--no-sqlite`). |

The engine boots the full event-driven stack — memory port, HAL, event bus,
worker pool, subsystems — runs `rate × 2` tics (~2 seconds), then drains and
shuts down cleanly. With SQLite enabled it creates or loads a profile at
`~/.openfps/profile.db` and accumulates playtime across runs; override the path
with the `OPENFPS_PROFILE_DB` environment variable.

---

## Checkstyle Only

```powershell
.\gradlew checkstyleMain
```

HTML report: `build/reports/checkstyle/main.html`

---

## Tests Only

```powershell
.\gradlew test
```

HTML report: `build/reports/tests/test/index.html`

---

## Android Build

Requires Android SDK 34 installed. Set `ANDROID_HOME`:

```powershell
$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"
.\gradlew build -Pandroid
```

The Android profile compiles all engine source into an AAR library.
Application code (game client, server) would live in a separate Android module.

---

## Gradle Wrapper — Quick Refresh

If the wrapper JAR is missing or stale:

```powershell
.\gradlew wrapper --gradle-version=8.10
```

Or download a fresh one manually from the Gradle releases.

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

```powershell
.\gradlew build -Dorg.gradle.java.installations.auto-detect=true
```

### Checkstyle errors

```powershell
.\gradlew checkstyleMain 2>&1 | Select-String "error"
```

### Build is slow

```powershell
.\gradlew build --parallel --build-cache
```

---

## CI/CD (GitHub Actions)

See `.github/workflows/` (add this directory when CI is set up). The workflow should:
- Run `gradlew build` on push to `main` and all PRs
- Upload Checkstyle HTML report as artifact
- Upload test results

---
