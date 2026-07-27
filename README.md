# OpenFPS

> Open-source peer-to-peer FPS game engine targeting the JVM. Event-driven, multi-threaded, hexagonal architecture.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-17%20LTS-ED8B00.svg)](https://adoptium.net/)
[![Gradle](https://img.shields.io/badge/Gradle-8.10-02303A.svg)](https://gradle.org/)
[![Tests](https://img.shields.io/badge/tests-129%20passing-brightgreen.svg)](BUILD.md)
[![Status](https://img.shields.io/badge/status-pre--alpha-blue.svg)](PLAN.md)

## Overview

OpenFPS is a from-scratch FPS game engine written in Java 17, designed around the original Doom subsystem architecture (D_ / P_ / R_ / S_ / G_ / W_ / Z_ / I_) adapted for modern JVM targets.

The engine is an **event queue processor**: subsystems communicate by publishing events to a shared bus, and a pool of N dedicated worker threads (N = logical CPU count / 2) consumes events and dispatches them to the target subsystem. Each subsystem is its own state machine. Every allocation goes through a single memory port.

**Currently in pre-alpha.** Phase 1.4 complete: 129 tests passing, Checkstyle clean, build green — ready for the desktop LWJGL3 adapter (Phase 1.5).

## Key Design Decisions

- **Event-driven architecture** — every subsystem interaction is an event; the worker pool dispatches them
- **Multi-threaded by default** — N = `logicalProcessorCount / 2` hot worker threads, pre-started at boot
- **Per-subsystem state machines** — `UNINITIALIZED → READY → ERROR → SHUTDOWN`, no shared state, no silent failures
- **Unified memory port** — one interface, two backends (`JvmMemoryPort` for GC-managed, `ZoneMemoryPort` for bulk-free)
- **Configurable frame rate** — 30 / 60 / 120 Hz, picked at boot via `--fps=N`, with drift-correction math
- **P2P networking first** — no authoritative server required for small matches (Phase 3+)
- **Adapter/port (hexagonal) architecture** — core engine has zero platform dependencies
- **Immutable by default** — `final` on every field, parameter, and most locals
- **Primitive-first** — no `Integer`/`Long`/`Boolean` in hot paths

## Quick Start

```powershell
# Clone
git clone https://github.com/nicholashutter/OpenFPS.git
cd OpenFPS

# Build (compiles + tests + checkstyle)
.\gradlew.bat build

# Run tests
.\gradlew.bat test

# Run at default 60 Hz (headless, ~2 second smoke test)
.\gradlew.bat run

# Run at 30 or 120 Hz
.\gradlew.bat run --args="--fps=30"
.\gradlew.bat run --args="--fps=120"
```

See [BUILD.md](BUILD.md) for full build instructions, [STYLE.md](STYLE.md) for code conventions, and [PLAN.md](PLAN.md) for the full subsystem specification and roadmap.

## Architecture

```
  +-----------+
  |  Engine   |  boot: memory → HAL → bus → pool → subsystems
  |   Main    |  single entry, sets up everything
  +-----+-----+
        |
        v
  +-----+-----------+
  |  Game Loop (D_) |  30/60/120 Hz, produces TickEvent
  +-----+-----------+
        |
        v
  +-----+-----------+
  |   Event Bus     |  single shared queue, blocking put
  +-----+-----------+  (backpressure when producers outpace consumers)
        |
        v
  +-------------------+      +-------------------+
  |   Worker Pool     | ---> | SubsystemRegistry |
  |  N = cores/2 hot  |      |  + CORE (shutdown)|
  |  threads, pre-    |      |  + P_ Gameplay    |
  |  started. Each:   |      |  + R_ Render      |
  |  take → dispatch  |      |  + S_ Audio       |
  |  → loop (release  |      |  + G_ Net         |
  |  back to pool)    |      |  + Z_ Memory      |
  +-------------------+      |  + I_ Hardware    |
                              +-------------------+
                              (W_ Resource lands in Phase 2)
        |
        v
  +-------------------+
  | Hardware Abstraction  desktop/  mobile/  nulladapter/
  +-------------------+
```

Subsystems are decoupled — they never call each other directly. Every interaction is an event flowing through the bus.

## Frame Rate

The engine runs at one of three rates. Anything else is rejected at startup.

| FPS | nanos/frame | use case |
|---|---|---|
| 30  | 33,333,333  | console, low-power |
| 60  | 16,666,666  | default, standard PC |
| 120 | 8,333,333   | high-refresh gaming |

Drift correction is built in: every frame's deadline is computed absolutely from a fixed origin (`startNanos + tic * nanosPerTic`) so two machines running the same code at the same time reach the same deadline at the same tic — required for P2P lockstep determinism. Reference: [Glenn Fiedler, "Fix Your Timestep"](https://gafferongames.com/post/fix_your_timestep/).

## Memory

Every allocation in the engine goes through `I_MemoryPort` — the rest of the code doesn't know which backend is in use.

- **`JvmMemoryPort`** (default) — `new byte[size]` + slot tracking. O(1) allocate, GC reclaims.
- **`ZoneMemoryPort`** — bump pointer on a pre-reserved heap + handle validation bitmap. O(1) allocate, O(N) bulk-free by tag, zero fragmentation.
- **`MemoryPortFactory`** — `createJvm(int)`, `createZone(int)`, `createSlab(int, int)` (Phase 2+).

The port is a strict state machine — invalid transitions throw `MemoryException`, never silent. 43 tests cover both backends: positive, negative, random, overflow, underflow, state machine, and tag isolation.

## Style Guide

This project enforces strict style conventions via Checkstyle. See [STYLE.md](STYLE.md).

Highlights:
- Braces on their own lines
- `final` on all fields, parameters, and preferred on locals
- Primitive-first — no `Integer`, `Long`, `Boolean` in hot paths
- No lambda chains; single-operation lambdas OK
- Javadoc at the beginning of every non-private method, citing sources
- Frame rates are config (closed enum), not constants

## Project Structure

```
openFPS/
├── src/main/java/com/openfps/engine/
│   ├── core/                  D_ engine entry, event bus, worker pool, subsystem state machines
│   │   ├── event/             I_EngineEvent + TickEvent, RenderFrameEvent, ShutdownEvent, etc.
│   │   ├── eventbus/          I_EventBusPort, SharedEventBus, EventBusFactory
│   │   ├── pool/              I_ThreadPoolPort, WorkerPool, ThreadPoolFactory
│   │   ├── subsystem/         ISubsystem, Subsystem, SubsystemRegistry
│   │   │   └── impl/          CoreSubsystem, GameplaySubsystem, RenderSubsystem, AudioSubsystem, NetSubsystem, HalSubsystem, MemorySubsystem
│   │   ├── EngineMain.java    composition root — the only core class that touches adapters
│   │   ├── GameLoop.java
│   │   ├── FrameRate.java     enum: FPS_30, FPS_60, FPS_120
│   │   └── GameConfig.java    immutable, factory methods
│   ├── common/                FixedMath (16.16 fixed-point), Constants, UserProfile
│   ├── gameplay/              port/I_GameplayPort  adapter/NullGameplayPort
│   ├── render/                port/I_RenderPort    adapter/NullRenderPort
│   ├── audio/                 port/I_AudioPort     adapter/NullAudioPort
│   ├── net/                   port/I_NetworkPort   adapter/NullNetworkPort
│   ├── resource/              port/I_WadPort       adapter/NullWadPort   (not yet registered — Phase 2)
│   ├── memory/                port/I_MemoryPort  adapter/{Jvm,Zone}MemoryPort  factory/MemoryPortFactory  MemoryException
│   └── hal/                   I_ Hardware abstraction
│       ├── port/              I_TimePort, I_InputPort, I_NetworkPort, I_FilePort, I_SystemInfoPort, I_UserProfilePort
│       ├── adapter/nulladapter/   headless stubs + MemoryUserProfilePort
│       └── adapter/sqlite/        SqliteAdapterFactory, SqliteUserProfilePort (Xerial)
├── src/main/resources/
│   └── logback.xml            SLF4J logging config
├── src/test/java/             129 JUnit 5 tests
├── PLAN.md                    full project plan, subsystem specs, roadmap
├── STYLE.md                   code style guide (enforced by Checkstyle)
├── AGENTS.md                  AI agent instructions
├── BUILD.md                   build commands reference
└── config/checkstyle/         Checkstyle configuration
```

Each subsystem package has its own `README.md` — the FDE entry point. Read in order: `core → common → hal → gameplay → render → audio → net → resource → memory`.

## Test Coverage

**129 tests, all passing.**

| Suite | Tests | Covers |
|---|---|---|
| `FrameRateTest` | 9 | per-rate math, parser, rejection of unsupported rates |
| `GameConfigTest` | 10 | factory methods, drift correction (1000-tic simulation) |
| `SharedEventBusTest` | 10 | FIFO, backpressure, drain, lifecycle |
| `WorkerPoolTest` | 7 | hot threads, parallel dispatch, error recovery |
| `SubsystemStateTest` | 10 | state machine transitions, error handling |
| `FixedMathTest` | 6 | 16.16 fixed-point arithmetic |
| `UserProfileTest` | 12 | field validation, `withXxx` copies, equals/hashCode |
| `MemoryUserProfilePortTest` | 15 | in-memory CRUD, state machine |
| `SqliteUserProfilePortTest` | 15 | SQLite CRUD, upsert, persistence, state machine |
| `MemoryPortTest` | 35 | both backends: positive, negative, random, overflow, underflow, state, tags |

Run with: `.\gradlew.bat test`. `.\gradlew.bat build` also runs Checkstyle over
main and test sources and fails the build on any violation.

## Contributing

1. All PRs must pass `gradlew build` (Checkstyle + tests)
2. New subsystems require port interfaces before any adapter implementation
3. Core engine never imports from `adapter/` packages — except `EngineMain`,
   the composition root, which must pick concrete implementations
4. Each subsystem has its own state machine
5. Frame rate changes require a new `FrameRate` enum value (not runtime config)
6. All public API must be documented with Javadoc citing sources
7. Update `PLAN.md` section 7 roadmap when completing implementation items

## License

MIT — see [LICENSE](LICENSE).
