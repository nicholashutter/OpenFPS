# BUILD.md — OpenFPS Build Reference

> How to build, test, and run OpenFPS on all supported platforms.

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java JDK | 17 LTS | Required. Use Eclipse Temurin or Adoptium. `java -version` should show 17 or higher |
| Gradle | 8.x | Bundled via wrapper — no install needed |
| Android SDK | 34+ | Optional. Only for `-Pandroid` profile |
| Git | Any recent | For version control |

### Installing Java 21 (if needed)

**Windows (Chocolatey):**
```powershell
choco install openjdk21 -y
```

**macOS (Homebrew):**
```bash
brew install openjdk@21
```

**Linux (apt):**
```bash
sudo apt install openjdk-21-jdk
```

After installing, set `JAVA_HOME`:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
```

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
.\gradlew run
```

> Currently exits early — main class is wired but subsystems are stubs.

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
2. Set Project SDK to Java 21
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
            "path": "C:/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot",
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
