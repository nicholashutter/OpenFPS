# OpenFPS

> Open-source peer-to-peer FPS game engine targeting the JVM (desktop, mobile, browser-compatible).

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-17%20LTS-ED8B00.svg)](https://adoptium.net/)
[![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A.svg)](https://gradle.org/)

## Overview

OpenFPS is a from-scratch FPS game engine written in Java 21, designed around the original Doom
subsystem architecture (D_ / P_ / R_ / S_ / G_ / W_ / Z_ / I_) adapted for modern JVM targets.
It uses an **adapter/port (hexagonal)** architecture so platform-specific code lives entirely at the edges.

**Currently in pre-alpha.** The project is scaffolding complete — architecture defined, build working,
subsystem ports stubbed. Real implementations are next.

## Key Design Decisions

- **P2P networking first** — no authoritative server required for small matches
- **Memory-first performance** — primitive types, zone allocator for hot paths, no boxed collections
- **Cross-platform JVM** — runs on Windows, Linux, Android, and any JVM 21+ target
- **Adapter/port architecture** — core engine has zero platform dependencies
- **Immutable by default** — `final` everywhere, mutability is opt-in and annotated

## Quick Start

```powershell
# Clone
git clone https://github.com/nicholashutter/OpenFPS.git
cd OpenFPS

# Build
.\gradlew build

# Run tests
.\gradlew test

# Run (when a main class is wired)
.\gradlew run
```

See [BUILD.md](BUILD.md) for full build instructions including Android builds.

## Architecture

```
+------------------------------------------------------------------+
|  Main Game Loop (core)                                          |
+--------+---------+---------+--------+--------+-----------------+
| P_     |   R_    |   S_    |   G_   |   W_   | Z_ Memory        |
| Game-  | Render  | Audio   | Net    | Resource               |
| play   | (stub)  | (stub)  |  P2P   | /WAD                    |
+--------+---------+---------+--------+--------+-----------------+
|  Hardware Abstraction Layer (hal) — adapter/port pattern       |
|  desktop/  mobile/  null/                                        |
+------------------------------------------------------------------+
```

See [PLAN.md](PLAN.md) for the full subsystem specification.

## Style Guide

This project enforces strict style conventions. See [STYLE.md](STYLE.md).

Highlights:
- Braces on their own lines
- `final` on all fields, parameters, and preferred on locals
- Primitive-first — no `Integer`, `Long`, `Boolean` in hot paths
- No lambda chains; single-operation lambdas OK
- Javadoc at the beginning of every non-private method

## Project Structure

```
openFPS/
├── src/main/java/com/openfps/engine/
│   ├── core/          # D_ Main game loop
│   ├── gameplay/      # P_ Player & physics
│   ├── render/        # R_ Rendering (stubbed)
│   ├── audio/         # S_ Audio (stubbed)
│   ├── net/           # G_ P2P networking
│   ├── resource/      # W_ WAD resource loading
│   ├── memory/        # Z_ Zone allocator
│   └── hal/           # I_ Hardware abstraction (ports + adapters)
│       ├── port/      # Interface definitions
│       └── adapter/   # desktop/ mobile/ nulladapter/
├── src/test/java/     # JUnit 5 tests
├── PLAN.md            # Full project plan & roadmap
├── STYLE.md           # Code style guide
├── AGENTS.md          # AI agent instructions
├── BUILD.md           # Build commands reference
└── config/checkstyle/ # Checkstyle enforcement
```

## Contributing

1. All PRs must pass `gradlew build` (checkstyle + tests)
2. New subsystems require port interfaces before any adapter implementation
3. Core engine never imports from `adapter/` packages
4. Update `PLAN.md` section 7 roadmap when completing implementation items

## License

MIT — see [LICENSE](LICENSE).
