# OpenFPS — Project Plan

> **Status**: Pre-alpha — Phase 1.4 complete. Event-driven engine, unified memory, multi-threaded worker pool, configurable 30/60/120 Hz, SQLite user-profile persistence.
> **Engine Version**: 0.1.0-SNAPSHOT
> **Target JVM**: 17 LTS (Java 17 source/target, runs on 17+)
> **Platforms**: Windows, Linux, Android (planned), JVM-compatible targets

---

## 1. Vision

OpenFPS is a ground-up FPS game engine written in Java targeting the JVM runtime. It prioritizes memory efficiency, cross-platform portability, and a clean adapter/port (hexagonal) architecture so that platform-specific code lives at the edges, never in the core. Networking is peer-to-peer first; no authoritative server required for small-scale matches.

The engine draws direct inspiration from the original Doom (id Software, 1993) subsystem layout — not as cargo culting, but because that architecture genuinely separates concerns well for a real-time game loop. We borrow the letter-prefix convention (D_, P_, R_, S_, G_, W_, Z_, I_) as a loving homage and a clear naming signal.

The engine is now an **event queue processor**: subsystems communicate exclusively by publishing events to a shared bus, and a pool of N dedicated worker threads (where N = logical CPU count / 2) consumes events and dispatches them to the target subsystem.

---

## 2. Architecture Overview — event-driven

```
+----------------------------------------------------------------+
|  ENGINE ENTRY   com.openfps.engine.core.EngineMain             |
|  Wires memory, HAL, bus, pool, subsystems                      |
+----------------------------------------------------------------+
                              |
                              v
+----------------------------------------------------------------+
|  D_ GAME LOOP   com.openfps.engine.core.GameLoop               |
|  Single thread, 30/60/120 Hz, produces events:                 |
|    TickEvent, ShutdownEvent                                   |
+----------------------------------------------------------------+
                              |
                              v
+----------------------------------------------------------------+
|  EVENT BUS      com.openfps.engine.core.eventbus.SharedEventBus|
|  LinkedBlockingQueue, blocking put (backpressure)              |
+----------------------------------------------------------------+
                              |
                              v
+----------------------------------------------------------------+
|  WORKER POOL    com.openfps.engine.core.pool.WorkerPool        |
|  N = logicalProcessorCount / 2 hot threads, pre-started        |
|  Each: take() -> dispatch() -> loop                            |
+----------------------------------------------------------------+
                              |
                              v
+----------------------------------------------------------------+
|  SUBSYSTEMS     com.openfps.engine.core.subsystem.impl.*       |
|  Per-subsystem state machine (UNINIT -> READY -> SHUTDOWN)     |
|                                                                  |
|  P_ Gameplay   R_ Render   S_ Audio   G_ Net   W_ Resource   |
|  Z_ Memory    I_ Hardware Abstraction                          |
+----------------------------------------------------------------+
                              |
                              v
+----------------------------------------------------------------+
|  HARDWARE ABSTRACTION  com.openfps.engine.hal                   |
|  Ports = interfaces, Adapters = platform impls                  |
|  desktop/  mobile/  null/                                       |
+----------------------------------------------------------------+
```

### Module dependency graph (no cycles)

```
core  ──►  core.event       (event types, factory)
      ──►  core.eventbus    (I_EventBusPort, SharedEventBus, factory)
      ──►  core.pool        (I_ThreadPoolPort, WorkerPool, factory)
      ──►  core.subsystem   (ISubsystem, base class, registry, impls)
      ──►  common           (FixedMath, Constants)
      ──►  gameplay/port
      ──►  render/port
      ──►  audio/port
      ──►  net/port
      ──►  resource/port
      ──►  memory/port
      ──►  hal/port

memory  ──►  common
hal     ──►  common
```

`core` is the only package that knows about the bus, the pool, and the subsystem state machine. The subsystem ports stay minimal (`init/shutdown/port-specific-methods`); the `Subsystem` wrapper in `core.subsystem` adds the state machine and event dispatch.

---

## 3. Subsystem Specification

Each subsystem lives in its own package under `com.openfps.engine.<name>`.
Every subsystem has:
- **port/** — interfaces (the "port" side of hexagonal arch)
- **adapter/** — concrete implementations (the "adapter" side)
- The subsystem itself (state machine + event handling) is in `core/subsystem/impl/`

### 3.1 Core — `com.openfps.engine.core` — **Phase 1.3 complete**

**D_ Game Loop & Engine**

- `EngineMain` — bootstrap: memory → HAL → system info → bus → registry → pool → loop
- `GameLoop` — single-threaded event producer at the configured rate (30/60/120 Hz)
- `FrameRate` — closed enum: `FPS_30`, `FPS_60`, `FPS_120` (120 is the cap)
- `GameConfig` — immutable (rate, maxTics) with factory methods
- `EventFactory` — sequence numbers + timestamps for events
- `EngineState` — engine-level state enum

**Event flow** (Phase 1.2):
- `I_EngineEvent` — base event interface (target subsystem, sequence, timestamp)
- Concrete events: `TickEvent`, `RenderFrameEvent`, `ShutdownEvent`, `MapLoadEvent`, `InputSampledEvent`, `NetworkPacketEvent`
- `I_EventBusPort` — bus port (single shared queue, blocking backpressure)
- `SharedEventBus` — `LinkedBlockingQueue` implementation
- `I_ThreadPoolPort` — pool port (N hot threads, pre-started)
- `WorkerPool` — drains the bus, dispatches to subsystems
- `EventBusFactory`, `ThreadPoolFactory` — system-level selectors

**Subsystem state machine** (Phase 1.2):
- `ISubsystem` — interface (id, state, init, shutdown, processEvent)
- `Subsystem` — base class with state machine + thread-safe `processEvent`
- `SubsystemId` — `CORE`, `P_`, `R_`, `S_`, `G_`, `W_`, `Z_`, `I_`
- `SubsystemState` — `UNINITIALIZED`, `READY`, `ERROR`, `SHUTDOWN`
- `SubsystemException` — invalid transitions throw
- `SubsystemRegistry` — lookup by ID, dispatch routing

**Subsystem wrapper**: `CoreSubsystem` — claims `SubsystemId.CORE` and handles
`ShutdownEvent`. Without it, every shutdown logged a "no subsystem registered"
warning, because `ShutdownEvent` targets CORE and nothing owned that ID.

### 3.2 Gameplay — `com.openfps.engine.gameplay` — **stub**

**P_ Player & World Logic**

- `PlayerState`: position (fixed-point), velocity, angle, pitch, health, inventory
- `Entity`: abstract base for all game objects (players, projectiles, pickups, doors)
- `PhysicsWorld`: collision detection, gravity, sliding along walls (BSP-assisted)
- `MapSubsector`: sector adjacency, portal handling
- `I_GameplayPort` — `tick(int)`, `loadMap(String)`, `spawnEntity(...)`, `removeEntity(...)`
- `NullGameplayPort` — stub

**Subsystem wrapper**: `GameplaySubsystem` — routes `TickEvent` to `port.tick()`, `MapLoadEvent` to `port.loadMap()`.

### 3.3 Render — `com.openfps.engine.render` — **stub**

**R_ Rendering**

- `BspTraverser`: walks the BSP tree front-to-back
- `WallClipper`: Sutherland-Hodgman clipping against view frustum
- `VisplaneBuilder`: screen-space horizontal band management
- `I_RenderPort` — `renderFrame(int)`
- `NullRenderPort` — stub

**Subsystem wrapper**: `RenderSubsystem` — routes `RenderFrameEvent` to `port.renderFrame()`.

### 3.4 Audio — `com.openfps.engine.audio` — **stub**

**S_ Sound**

- `SoundEngine`: voice allocation, mix loop
- `SoundEmitter`: 3D position + velocity
- `I_AudioPort` — `playSfx`, `playMusic`, `stopAll`
- `NullAudioPort` — stub

**Subsystem wrapper**: `AudioSubsystem` — init/shutdown only for now (event types for SFX/music are Phase 6 work).

### 3.5 Network — `com.openfps.engine.net` — **stub**

**G_ Peer-to-Peer Networking**

- `PeerConnection` — per-peer state (address, RTT, ack window, loss stats); no socket
- `TicCmdBuffer` — ring buffer of `TicCmd` per peer, indexed by tic number
- `RedundantSender` — packs all cmds since the peer's last ack into every packet
- `SnapshotDelta` — diff-based state serialization between tics
- `Discovery` — LAN peer discovery via UDP broadcast
- `I_NetworkPort` — `connect`, `disconnect`, `broadcastTicCmd`, `pollTicCmd`, `broadcastMapChange`, `discoverPeers`, `connectedPeerCount`
- `NullNetworkPort` — stub

**Transport**: UDP over a single non-blocking `java.nio.channels.DatagramChannel`, demultiplexed by source address. Reliability is redundant input redelivery, not retransmission — TCP is ruled out by head-of-line blocking (a 200 ms `TCP_RTO_MIN` stall is 12+ tics at 60 Hz). No dependency added; native options (Valve GameNetworkingSockets) were evaluated and rejected on the Android build matrix. Full rationale and the options table: `net/README.md` § "Transport decision".

**Subsystem wrapper**: `NetSubsystem` — routes `NetworkPacketEvent` (Phase 3 will wire actual parsing).

### 3.6 Resource — `com.openfps.engine.resource` — **stub**

**W_ WAD File Management**

- `WadReader` — opens `.wad`, reads lump directory
- `LumpCache` — demand-loaded, reference-counted
- `MapLumpParser` — THINGS, LINEDEFS, SECTORS, etc.
- `ImageDecoder` — DOOM-format patches and flats
- `I_WadPort` — `open`, `close`, `readLump`, `precacheLump`, `flushCache`, `lumpCount`
- `NullWadPort` — stub

**Subsystem wrapper**: none yet. Resource is the one subsystem with no wrapper
and no registration in `EngineMain`, so `SubsystemId.W_` is currently unused and
nothing can route an event to it. The port interface and its WAD-format spec are
kept as the Phase 2 design; the wrapper lands with `WadReader`.

### 3.7 Memory — `com.openfps.engine.memory` — **Phase 1.1 complete**

**Z_ Unified Memory Port**

- **One interface, multiple backends** — engine never instantiates a port directly
- `I_MemoryPort` — `init(int)`, `shutdown`, `reset`, `allocate(int, int)`, `free(int)`, `freeByTag(int)`, `totalBytes`, `allocatedBytes`, `freeBytes`, `maxAllocatable`, `handleCount`, `sizeOf`
- `State` enum: `UNINITIALIZED`, `READY`, `ACTIVE`, `SHUTDOWN`, `ERROR`
- All operations validate state and throw `MemoryException` on invalid transition
- Returns `int` handles (not raw pointers) — engine never dereferences
- `NULL_HANDLE = -1` for failed allocations

**Backends:**
- `JvmMemoryPort` (default) — `new byte[size]` + slot tracking, O(N) freeByTag
- `ZoneMemoryPort` — bump pointer on a pre-reserved heap + `boolean[] live` bitmap for handle validation
- `MemoryPortFactory` — `createJvm(int)`, `createZone(int)`, `createSlab(int, int)` (Phase 2+)
- `MemoryException` — dedicated exception

**Subsystem wrapper**: `MemorySubsystem` — passes through to port shutdown.

### 3.8 HAL — `com.openfps.engine.hal` — **Phase 1.4 complete**

**I_ Hardware Abstraction Layer**

- `I_TimePort` — `millis()`, `nanos()` (both monotonic), `epochMillis()` (wall clock, for persisted timestamps), `init()`, `shutdown()`
- `I_InputPort` — `sampleInput(int)`, `isShutdownRequested()`, `init()`, `shutdown()`
- `I_NetworkPort` — `send`, `receive`, `bind`, `close`, `processTic`, `init`, `shutdown`
- `I_FilePort` — `open`, `exists` + nested `I_FileHandle`
- `I_SystemInfoPort` (Phase 1.2) — `logicalProcessorCount`, `physicalProcessorCount`, `totalMemoryBytes`, `freeMemoryBytes`, `osName`, `osVersion`, `javaVersion`, `state()`
- `I_UserProfilePort` (Phase 1.4) — `findById`, `findAll`, `save` (upsert), `delete`, `count`, `generateNewId`, `init`, `shutdown`, `state()`

**Adapters** (in `hal/adapter/`):
- `nulladapter/` — headless stubs, used by all CI / smoke tests. Includes `MemoryUserProfilePort` (in-memory profiles).
- `sqlite/` — `SqliteAdapterFactory` + `SqliteUserProfilePort` (Xerial SQLite JDBC). Real on-disk profile persistence at `<userHome>/.openfps/profile.db`, overridable via `OPENFPS_PROFILE_DB`. Null ports for everything else.
- `desktop/` — LWJGL3 + OpenAL + NIO sockets (Phase 1.5+)
- `mobile/` — Android Canvas + AudioTrack + Room (Phase 3+)

**NullSystemInfoPort** returns `Runtime.availableProcessors()` (logical cores). Worker pool size = `max(1, logicalCores / 2)`.

---

## 4. Data Layout Conventions

- **Fixed-point arithmetic**: `int` storing value × 65536 (1.0 = 0x10000). See `common/FixedMath.java`.
- **Angles**: degrees × 65536 in `int` (360° = 360 × 65536 = 23,592,960)
- **Coordinates**: `x`, `y`, `z` each `int` fixed-point
- **Entity ID**: `int` — unique per-level, rolled over on map load
- **Tic number**: `int` — monotonically increasing from game start, no cap
- **Frame budget**: `long` nanos, computed from `FrameRate` enum, NOT a constant

---

## 5. Frame Rate Configuration (Phase 1.3)

The engine runs at one of three rates: **30, 60, or 120 Hz**. Anything else is rejected.

### Per-rate frame budget

| FPS | nanos/frame | drift/frame | use case |
|---|---|---|---|
| 30  | 33,333,333  | 0.33 ns     | console, low-power |
| 60  | 16,666,666  | 0.67 ns     | default, standard PC |
| 120 | 8,333,333   | 0.33 ns     | high-refresh gaming |

### Drift correction (the math)

Naive additive wait (`nextDeadline += budget`) accumulates per-frame rounding error and breaks P2P lockstep determinism. Instead, every iteration computes the deadline absolutely from a fixed origin:

```java
final long startNanos = timePort.nanos();
for (int tic = 0; running; tic++) {
    long deadlineNanos = startNanos + ((long) tic * nanosPerTic);
    long waitNanos = deadlineNanos - timePort.nanos();
    if (waitNanos > 0) waitNanos(waitNanos);
    publishTickEvent(tic, nanosPerTic);
}
```

This is verified by `GameConfigTest.shouldNotDriftAt120Fps` and `shouldNotDriftAt30Fps` — both simulate 1000 iterations and assert total drift < 1 microsecond. Reference: Glenn Fiedler, "Fix Your Timestep" — https://gafferongames.com/post/fix_your_timestep/

### CLI

```powershell
.\gradlew.bat run --args="--fps=30"
.\gradlew.bat run --args="--fps=60"   # default
.\gradlew.bat run --args="--fps=120"
```

---

## 6. Build Configuration

| Target | Toolchain | Notes |
|---|---|---|
| Desktop (Win/Linux) | Java 17 + Gradle 8.10 | Java 17 source/target; runs on JVM 17+ |
| Android | Java 17 source, Android SDK 34 | Planned (Phase 3+) |
| Headless / Test | Java 17 only | null HAL adapter |

### Dependencies

| Library | Version | Purpose |
|---|---|---|
| Gradle | 8.10 | Build |
| SLF4J | 2.0.16 | Logging facade (industry standard) |
| Logback | 1.5.12 | Logging backend |
| Xerial SQLite JDBC | 3.46.1.0 | User profile persistence (pure Java, no native deps) |
| JUnit Jupiter | 5.11.4 | Testing |
| AssertJ | 3.26.3 | Test assertions |
| Checkstyle | 10.18.0 | Style enforcement (enforces STYLE.md; wired to `build`, `maxWarnings = 0`) |
| LWJGL | 3.3.4 (planned) | Desktop graphics/audio/net |

---

## 7. Project Roadmap

### Phase 0 — Scaffolding — **done**
- [x] Git repo, project plan, style guide
- [x] Gradle build (Kotlin DSL)
- [x] Package skeleton with port/adapter stubs
- [x] Checkstyle config

### Phase 0.5 — Per-subsystem docs + math demystification — **done**
- [x] README.md in every subsystem package (2-line pitch + full design)
- [x] Math references (DOOM source, Quake 3 source, original papers)
- [x] All port interfaces have inline Javadoc citing sources

### Phase 1.1 — Unified Memory Port — **done**
- [x] `I_MemoryPort` state machine (`UNINITIALIZED → READY → ACTIVE → SHUTDOWN/ERROR`)
- [x] `JvmMemoryPort` (default) + `ZoneMemoryPort` (bulk-free)
- [x] `MemoryPortFactory` (system-level selector)
- [x] `MemoryException` for all invalid state
- [x] 43 tests across both backends (positive, negative, random, overflow, underflow, state machine, tags)

### Phase 1.2 — Event-driven engine + multi-threaded worker pool — **done**
- [x] `I_EngineEvent` base + 6 concrete events
- [x] `SharedEventBus` (single shared queue, blocking backpressure)
- [x] `WorkerPool` (N = logicalCores / 2 hot threads, pre-started)
- [x] `Subsystem` state machine + `SubsystemRegistry`
- [x] `I_SystemInfoPort` (logical core count from HAL)
- [x] `EngineMain` full event-driven bootstrap
- [x] 27 new tests (10 bus + 7 pool + 10 state)

### Phase 1.3 — Configurable frame rate (30/60/120) — **done**
- [x] `FrameRate` enum (closed set, 120 cap)
- [x] `GameConfig` (immutable, factory methods)
- [x] Drift-correction math (absolute deadline)
- [x] CLI `--fps=N` parsing
- [x] 19 new tests (9 FrameRate + 10 GameConfig including drift tests)

### Phase 1.4 — User profile persistence (Xerial SQLite) — **done**
- [x] `I_UserProfilePort` interface with state machine
- [x] `UserProfile` immutable record with validation
- [x] `SqliteUserProfilePort` (Xerial SQLite JDBC 3.46.1.0)
- [x] `MemoryUserProfilePort` (in-memory, for tests/headless)
- [x] `SqliteAdapterFactory` (real desktop-ish factory)
- [x] `EngineMain` loads/saves profile on boot/shutdown
- [x] 42 new tests (15 in-memory + 15 SQLite + 12 UserProfile validation)
- [x] Persistence verified: same UUID loaded across restarts, playtime accumulates

### Phase 1.5 — Desktop HAL adapter (LWGJL3) — **next**
- [ ] `DesktopTimePort` using LWJGL3 `GLFW.glfwGetTime()`
- [ ] `DesktopInputPort` using LWJGL3 Keyboard/Mouse callbacks
- [ ] `DesktopNetworkPort` using `java.nio.channels.DatagramChannel` — datagram-only, one preallocated **direct** `ByteBuffer` reused per receive (a heap buffer makes the JDK copy through a temporary direct one on every call)
- [ ] Basic OpenGL window for render testing
- [ ] Confirm event-driven architecture handles real input timing

### Phase 2 — WAD file loader — **planned**
- [ ] `WadReader` — header + directory parse
- [ ] `LumpCache` — demand-loaded, ref-counted
- [ ] `MapLumpParser` — THINGS / LINEDEFS / SECTORS
- [ ] `ImageDecoder` — patch + flat decode
- [ ] `BlockmapBuilder` — pre-compute BLOCKMAP from LINEDEFS

### Phase 3 — Networking — **planned**
- [x] Transport decision recorded (`net/README.md` § "Transport decision") — UDP + redundant redelivery, no dependency added
- [ ] `PeerConnection` (peer state — address, RTT, ack window; no socket)
- [ ] `TicCmdBuffer` (lockstep) — fixed 60 Hz sim tick, decoupled from render
- [ ] `RedundantSender` — redundant input redelivery + 64-bit ack bitfield
- [ ] `SnapshotDelta` (encode/decode)
- [ ] `Discovery` (LAN broadcast)

### Phase 4 — Gameplay — **planned**
- [ ] `LagCompensator` (rewind for hits) — moved from Phase 3; under pure lockstep there is nothing to rewind, so this only becomes meaningful once prediction/snapshot exists
- [ ] `PlayerState` data class
- [ ] `Entity` base + concrete types (monster, projectile, pickup, door)
- [ ] `PhysicsWorld.moveWithSlide(player, dx, dy)` — collision + slide
- [ ] `MapLoader` — read THINGS / LINEDEFS / SECTORS from WAD
- [ ] `BspTraverser` — leaf lookup, reused in both gameplay and render

### Phase 5 — Render — **planned**
- [ ] `BspTraverser.walk(rootNode, playerPos, clipBox)`
- [ ] `WallClipper.clipSides(seg, clipBox)` — Sutherland-Hodgman
- [ ] `VisplaneBuilder.open/close/spans`
- [ ] `ColumnRenderer.drawColumn(x, y1, y2, texture, texX)`
- [ ] `FrameBuffer.blit(palette)` — for adapter upload

### Phase 6 — Audio — **planned**
- [ ] `SoundEngine` — voice allocation, mix loop
- [ ] `SoundEmitter` — 3D position + velocity
- [ ] `MusicPlayer` — OGG/MIDI streaming
- [ ] `PcmLoader` — read SFX from WAD lumps

---

## 8. Test Coverage Summary

**129 tests, all passing.**

| Suite | Tests | Coverage |
|---|---|---|
| `FrameRateTest` | 9 | per-rate math, parser, rejection |
| `GameConfigTest` | 10 | factories, drift correction (1000-tic sim) |
| `SharedEventBusTest` | 10 | FIFO, backpressure, drain, lifecycle |
| `WorkerPoolTest` | 7 | hot threads, parallel dispatch, error recovery |
| `SubsystemStateTest` | 10 | transitions, error handling, thread-safety |
| `FixedMathTest` | 6 | fixed-point arithmetic |
| `UserProfileTest` | 12 | field validation, withXxx copies, equals/hashCode |
| `MemoryUserProfilePortTest` | 15 | in-memory CRUD, state machine |
| `SqliteUserProfilePortTest` | 15 | SQLite CRUD, upsert, persistence, state machine |
| `MemoryPortTest` | 35 | both backends (7 `@Nested` groups): positive, negative, random, overflow, underflow, state machine, tags |

Run with: `.\gradlew.bat test`. `.\gradlew.bat build` additionally runs
Checkstyle over main and test sources and fails on any violation.

---

## 9. Contribution Guidelines

1. All PRs must pass `gradle build` (Checkstyle + tests)
2. New subsystems require port interfaces before any adapter implementation
3. Core engine never imports from `adapter/` packages — the sole exception is
   the composition root, `EngineMain`, which has to pick concrete implementations
4. Each subsystem has its own state machine — no shared state machines
5. New frame rates require a new `FrameRate` enum value (not runtime config)
6. All public API must be documented with Javadoc citing sources
7. Breaking changes to public API require a major version bump

---

## 10. Document-to-Code Mapping

This plan is the source of truth. When code is written:
- Update the relevant subsystem section with implementation notes
- Mark `[x]` items in the roadmap as they complete
- Keep section numbers stable; append `-impl` notes below the specification

Per-package READMEs in `src/main/java/com/openfps/engine/<subsystem>/README.md` are the FDE entry point for that subsystem — read them in order: `core → common → hal → gameplay → render → audio → net → resource → memory`.
