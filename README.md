# OpenFPS

> Open-source peer-to-peer FPS game engine targeting the JVM. Event-driven, multi-threaded, hexagonal architecture, and a software triangle rasterizer with no GPU shading path.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-17%20LTS-ED8B00.svg)](https://adoptium.net/)
[![Gradle](https://img.shields.io/badge/Gradle-8.13-02303A.svg)](https://gradle.org/)
[![Tests](https://img.shields.io/badge/tests-1524%20passing-brightgreen.svg)](BUILD.md)
[![Status](https://img.shields.io/badge/status-pre--alpha-blue.svg)](PLAN.md)

## Overview

OpenFPS is a from-scratch FPS game engine written in Java 17, designed around the original Doom subsystem architecture (D_ / P_ / R_ / S_ / G_ / W_ / Z_ / I_) adapted for modern JVM targets.

The engine is an **event queue processor**: subsystems communicate by publishing events to a shared bus, and a pool of N dedicated worker threads (N = logical CPU count / 2) consumes events and dispatches them to the target subsystem. Each subsystem is its own state machine. Every allocation goes through a single memory port.

**Currently in pre-alpha, and it is a game now rather than a demo.** A blocky title screen, a single-player match against seven bots that patrol and shoot back, hitscan combat with a crosshair and outlined opponents, and a UDP transport that two live processes have been measured exchanging. It runs on a phone: the same rasterizer, the same match, driven by a thumbstick. 1524 tests passing, Checkstyle clean, build green.

What is *not* yet true: remote players are not simulated into bodies you can see, and the Android build boots the engine but never puts the world on screen. Both are stated precisely in [AGENTS.md](AGENTS.md).

## Key Design Decisions

- **Event-driven architecture** — every subsystem interaction is an event; the worker pool dispatches them
- **Multi-threaded by default** — N = `logicalProcessorCount / 2` hot worker threads, pre-started at boot
- **Software rendering** — a multi-threaded triangle rasterizer. The GPU is used only to blit one finished framebuffer per frame
- **Per-subsystem state machines** — `UNINITIALIZED → READY → ERROR → SHUTDOWN`, no shared state, no silent failures
- **Unified memory port** — one interface, two backends (`JvmMemoryPort` for GC-managed, `ZoneMemoryPort` for bulk-free)
- **Configurable frame rate** — 30 / 60 / 120 Hz, picked at boot via `--fps=N`, with drift-correction math
- **P2P networking first** — no authoritative server required for small matches (Phase 3+)
- **Adapter/port (hexagonal) architecture** — core engine has zero platform dependencies
- **Immutable by default** — `final` on every field, parameter, and most locals
- **Primitive-first** — no `Integer`/`Long`/`Boolean` in hot paths

## Quick Start

```powershell
git clone https://github.com/nicholashutter/OpenFPS.git
cd OpenFPS

# Build: compiles + tests + Checkstyle
.\gradlew.bat build

# Headless smoke test — 120 tics, about 2 seconds, no window
.\gradlew.bat :engine:run
.\gradlew.bat :engine:run --args="--fps=30"
```

> **Do not use bare `.\gradlew.bat run`.** Both `:engine` and `:desktop` apply the
> `application` plugin, so `run` now resolves to *two* tasks and launches both.
> Name the module you mean.

Running the playable demo needs art, which is not in the repository — see
[Running the demo](#running-the-demo).

See [BUILD.md](BUILD.md) for full build instructions, [STYLE.md](STYLE.md) for code
conventions, [PLAN.md](PLAN.md) for the subsystem specifications and roadmap,
[docs/ASSETS.md](docs/ASSETS.md) for the render target and asset budgets, and
[docs/DEMO_ASSETS.md](docs/DEMO_ASSETS.md) for demo art provenance.

## Running the demo

The demo is a first-person view of a room built from Kenney's CC0 kits, with a
blaster held in view space, mouse-look and WASD movement.

**A fresh clone cannot run it yet.** Demo art is gitignored — the repository
records where the assets came from, not the assets themselves. Two steps:

```powershell
# 1. Download and unzip the two CC0 packs named in docs/DEMO_ASSETS.md,
#    then convert them into the runtime .ofm format:
.\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=C:\path\to\unzipped\packs

# 2. Run it
.\gradlew.bat :desktop:run
```

Without `-PkenneyRaw`, step 1 still succeeds but emits **only a 60-triangle
greybox room and no weapon** — the deliberate fallback for when no pack has been
staged. With no models at all, `:desktop:run` reports the problem and exits with
code 3 rather than opening an empty window.

To look at the renderer without a window, both preview harnesses are headless:

```powershell
# Render the four demo shots to PNGs (point the output outside the repo)
.\gradlew.bat :tools:demoPreview -PdemoOut=C:\tmp\demo

# Same, but also time 300 frames on 8 worker threads
.\gradlew.bat :tools:demoPreview -PdemoOut=C:\tmp\demo -PdemoThreads=8 -PdemoFrames=300

# Render a single model
.\gradlew.bat :tools:renderPreview "--args=--model=assets\models\blaster-b.ofm --out=C:\tmp\gun.png"
```

## Modules

| Module | Contains | Notes |
|---|---|---|
| `:engine` | The whole engine: core, memory, HAL ports, gameplay, render, net, resource, audio, demo | Pure Java 17, **no platform dependencies**. Builds and tests headless anywhere |
| `:gdxshared` | The libGDX code that is not platform-specific — framebuffer presentation, the welcome screen, the UI state machine, the input accumulator | Depends on libGDX **core** and no backend. Both launchers use it; neither writes it twice |
| `:desktop` | libGDX LWJGL3 backend — window, mouse and keyboard, `DesktopLauncher` | |
| `:tools` | Build-time only: glTF → `ModelFormat` converter, mip generation, headless preview harnesses | Nothing depends on it. `verifyToolsIsolation` fails the build if it ever reaches a runtime classpath |
| `:android` | libGDX Android backend — `AndroidLauncher`, the touch control scheme, Room-backed profile storage | **Playable.** Only included when an Android SDK is present; without `ANDROID_HOME`, `settings.gradle.kts` silently skips it, so a green `gradlew build` does not prove `:android` compiles |

## Renderer

A multi-threaded **software triangle rasterizer**. There is no GPU shading path:
the engine produces a finished 32-bit framebuffer each frame and the platform
adapter uploads it as a single texture.

- **Visibility** — z-buffer storing 1/w, greater-passes. BSP was retired as the visibility algorithm
- **Geometry** — near-plane Sutherland-Hodgman clipping in homogeneous space, backface cull, perspective-correct interpolation
- **Shading** — mipmapped bilinear sampling with the −0.5 texel-centre offset; baked vertex lighting, no dynamic lights
- **Threading** — triangles are binned to 64×64 tiles; each worker owns its tiles outright and is the only writer to those regions of the colour and depth buffers, so the depth buffer needs no synchronisation
- **Two passes per frame** — the world, then the view-space viewmodel with a depth clear between them, which is what stops the held weapon clipping through walls

**Measured**, not estimated, at 1280x720 on the 295-instance demo room
(Intel Core Ultra 7 155H): **p50 4.9 ms** at 8 workers, 3.9 ms at 16, against
22.1 ms serial. Output is bit-identical across every worker count — the tile
ownership rule makes the parallel result reproducible, not merely close.

720p is the shipping target and the number is measured rather than chosen:
1080p costs 10.3 ms per frame in fill alone, which consumes the entire renderer
budget inside a 16.7 ms frame before a single triangle is considered.

[docs/ASSETS.md](docs/ASSETS.md) § 2 is the canonical render target;
[`render/README.md`](engine/src/main/java/com/openfps/engine/render/README.md)
is the full specification — every formula, citation, and open question.

## Architecture

```
  +-----------+
  |  Engine   |  boot: memory → HAL → bus → pool → subsystems
  |   Main    |  single entry, sets up everything
  +-----+-----+
        |
        v
  +-----+-----------+
  |  Game Loop (D_) |  30/60/120 Hz, produces TickEvent,
  +-----+-----------+  ShutdownEvent and RenderFrameEvent
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
  |                   |      |  + I_ Hardware    |
  |  Also does        |      +-------------------+
  |  submitParallel   |      (W_ Resource is declared but
  |  fan-out for the  |       never registered — see PLAN.md)
  |  rasterizer tiles |
  +-------------------+
        |
        v
  +-----------------------+
  | Hardware Abstraction  |  desktop/  nulladapter/  sqlite/
  +-----------------------+
        |
        v
  +-----------------------+
  | :desktop / :android   |  I_WindowPort + I_FrameCallback
  |  presentation         |  upload the finished framebuffer
  +-----------------------+
```

Subsystems are decoupled — they never call each other directly. Every
interaction is an event flowing through the bus. The one exception is the render
path: `GameLoop` publishes a `RenderFrameEvent`, `RenderSubsystem` rasterizes
into a framebuffer, and the platform's `I_FrameCallback` presents it.

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

The port is a strict state machine — invalid transitions throw `MemoryException`, never silent. 35 tests cover both backends: positive, negative, random, overflow, underflow, state machine, and tag isolation.

There is one sanctioned exception to "every allocation goes through the port":
the rasterizer's `Framebuffer` allocates its `int[]` colour and `float[]` depth
arrays directly, because the port hands out byte-addressed handles and the
rasterizer needs typed arrays the JIT can bounds-check-eliminate. See
[STYLE.md](STYLE.md) and `render/README.md` § 11(a).

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
OpenFPS/
├── engine/src/main/java/com/openfps/engine/    :engine — platform-free
│   ├── core/                  D_ engine entry, event bus, worker pool, subsystem state machines
│   │   ├── event/             I_EngineEvent + TickEvent, RenderFrameEvent, ShutdownEvent, etc.
│   │   ├── eventbus/          I_EventBusPort, SharedEventBus, EventBusFactory
│   │   ├── pool/              I_ThreadPoolPort, WorkerPool, ThreadPoolFactory, I_ParallelJob
│   │   ├── subsystem/impl/    Core, Gameplay, Render, Audio, Net, Hal, Memory subsystems
│   │   ├── EngineMain.java    composition root — the only core class that touches adapters
│   │   ├── EngineSession.java non-blocking start/stop, used by the windowed launchers
│   │   ├── GameLoop.java      FrameRate.java      GameConfig.java
│   ├── common/                FixedMath (16.16 fixed-point), Constants, UserProfile
│   ├── gameplay/              PlayerController, PlayerInputView, I_PlayerInput, ports
│   ├── render/                Framebuffer, Camera, Rasterizer, SpanRenderer, TriangleClipper,
│   │                          TextureSampler, MipChain, ModelFormat, Scene, SoftwareRenderPort
│   ├── demo/                  DemoScene, DemoGameplayPort — the playable demo's content
│   ├── audio/                 port/I_AudioPort     adapter/NullAudioPort  (stub)
│   ├── net/                   TicCmd, TicCmdBuffer, AckWindow, PeerConnection, RedundantSender
│   ├── resource/              WadReader, LumpCache, MapLumpParser, WadFilePort (built, unregistered)
│   ├── memory/                port/I_MemoryPort  adapter/{Jvm,Zone}MemoryPort  factory  exception
│   └── hal/                   I_ Hardware abstraction
│       ├── port/              I_TimePort, I_InputPort, I_WindowPort, I_FrameCallback, InputState,
│       │                      I_DatagramPort, I_FilePort, I_SystemInfoPort, I_UserProfilePort
│       └── adapter/           nulladapter/  sqlite/  desktop/ (time, datagram)
├── desktop/src/main/java/com/openfps/desktop/   :desktop — libGDX LWJGL3
│                              GdxWindowPort, GdxInputPort, InputAccumulator,
│                              FramebufferPresenter, MainMenuScreen, DesktopLauncher
├── android/src/main/java/com/openfps/android/   :android — libGDX Android + Room
├── tools/src/main/java/com/openfps/tools/       :tools — build-time only
│                              GltfConverter, ModelBuilder, MipGenerator, ProceduralRoom,
│                              DemoPreviewMain, RenderPreviewMain
├── assets/                    gitignored — staged glTF and converted .ofm models
├── docs/ASSETS.md             render target, budgets, measured costs
├── docs/DEMO_ASSETS.md        demo art provenance, licences, SHA-256s
├── PLAN.md   STYLE.md   AGENTS.md   BUILD.md
└── config/checkstyle/         Checkstyle configuration
```

Each engine package has its own `README.md` — the entry point for that
subsystem. Read in order: `core → common → hal → gameplay → render → demo →
audio → net → resource → memory`.

## Documentation

Every module and most packages carry a `README.md`, and there is a root set
(`AGENTS.md`, `BUILD.md`, `PLAN.md`, `STYLE.md`) plus `docs/`. To read it as a
website rather than a folder of files:

```
gradlew :tools:buildDocsSite
```

Renders all of it into `docs/site/` — sidebar, per-page contents, cross-links,
dark mode, no external assets. The task fails if any cross-document link does
not resolve. The Markdown stays the source of truth.

## Test Coverage

**1524 tests, all passing** — 1131 `:engine`, 79 `:gdxshared`, 118 `:desktop`, 73 `:tools`, 123 `:android`.
These are *distinct* tests. An earlier count said 1516 and was slightly wrong in a
specific way: `:android:test` builds a debug and a release variant and runs the
same suite twice, so a raw tally of its XML double-counts every Android test.

| Module | Tests | Largest suites |
|---|---|---|
| `:engine` | 828 | render 316, resource 101, hal 91, net 87, gameplay 77, core 68, memory 35, demo 35, common 12 |
| `:desktop` | 99 | window port lifecycle, input accumulation, presentation wiring |
| `:tools` | 73 | glTF conversion, model round-trip, mip generation, budget enforcement |

Run with `.\gradlew.bat test`. `.\gradlew.bat build` also runs Checkstyle over
main and test sources and fails the build on any violation
(`maxWarnings = 0`).

Two properties are worth calling out because they are enforced by tests rather
than convention:

- **Renderer determinism** — the demo shots are rendered at every worker count
  from serial through 16 and compared byte-for-byte.
- **Simulation determinism** — `PlayerController` is checked at the constant-pool
  level to prove it never references `java/lang/Math`, only `StrictMath`. Lockstep
  P2P needs transcendentals to agree bit-for-bit across machines, and only
  `StrictMath` guarantees that.

## Contributing

1. All PRs must pass `gradlew build` (Checkstyle + tests)
2. New subsystems require port interfaces before any adapter implementation
3. Engine code never imports from `adapter/` packages — except each module's
   composition root, which must pick concrete implementations. Today that means
   `EngineMain` for `:engine` and `DesktopLauncher` / `AndroidLauncher` for the
   platform modules
4. Each subsystem has its own state machine
5. Frame rate changes require a new `FrameRate` enum value (not runtime config)
6. All public API must be documented with Javadoc citing sources — specifications
   and papers, not GPL-licensed source repositories
7. Update `PLAN.md` section 7 roadmap when completing implementation items

## License

MIT — see [LICENSE](LICENSE). Demo art is CC0 by Kenney; provenance and
licence terms are recorded in [docs/DEMO_ASSETS.md](docs/DEMO_ASSETS.md).
