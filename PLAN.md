# OpenFPS — Project Plan

> **Status**: Pre-alpha — Phases 0 through 1.5 complete, plus Phase 5 (render). Event-driven engine, unified memory, multi-threaded worker pool, configurable 30/60/120 Hz, SQLite user-profile persistence, desktop HAL with a real window and input, and a multi-threaded software triangle rasterizer driving a playable first-person demo at 1280x720. Phase 2 (WAD) is partly done and unregistered; Phase 3 (net) has its lockstep primitives built but no socket wired. **1239 tests** — 977 `:engine`, 125 `:desktop`, 73 `:tools`, 64 `:android`.
> **Engine Version**: 0.1.0-SNAPSHOT
> **Target JVM**: 17 LTS (Java 17 source/target, runs on 17+)
> **Platforms**: Windows, Linux, macOS (`:desktop`, libGDX LWJGL3 backend); Android (`:android`, libGDX Android backend). `:engine` is platform-free and runs headless on any JVM 17+.
> **Renderer**: multi-threaded software triangle rasterizer — no GPU shading path. The platform layer exists to open a window, pump input, and upload one finished framebuffer per frame. See `docs/ASSETS.md` § 2 and `render/README.md`.

---

## 1. Vision

OpenFPS is a ground-up FPS game engine written in Java targeting the JVM runtime. It prioritizes memory efficiency, cross-platform portability, and a clean adapter/port (hexagonal) architecture so that platform-specific code lives at the edges, never in the core. Networking is peer-to-peer first; no authoritative server required for small-scale matches.

The engine draws direct inspiration from the original Doom (id Software, 1993) subsystem layout — not as cargo culting, but because that architecture genuinely separates concerns well for a real-time game loop. We borrow the letter-prefix convention (D_, P_, R_, S_, G_, W_, Z_, I_) as a loving homage and a clear naming signal.

The engine is now an **event queue processor**: subsystems communicate exclusively by publishing events to a shared bus, and a pool of N dedicated worker threads (where N = logical CPU count − 1) consumes events and dispatches them to the target subsystem.

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
|    TickEvent, RenderFrameEvent, ShutdownEvent                  |
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
|  desktop/  nulladapter/  sqlite/                                 |
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

demo    ──►  core            (FrameRate)
        ──►  gameplay        (PlayerController, I_PlayerInput)
        ──►  hal/port        (InputState)
        ──►  render/adapter  (Scene, ModelFormat, Camera)

gameplay ──►  hal/port          (InputState — the latched input snapshot)
         ──►  render/adapter    (Camera, Vec3 — PlayerController.camera())
         ──►  core              (FrameRate — derives move speed from the tic rate)
```

The three `gameplay` edges were added with `PlayerController` and are worth a
note, because the middle one looks wrong at a glance. `STYLE.md` § 1.1 forbids
an **adapter** importing core engine packages; it does not forbid a subsystem
using another subsystem's public types, and there is precedent —
`render.adapter` imports `core.pool` and `hal.port`, and `resource.adapter`
imports `memory.port`. `camera(...)` is the only method carrying the render
edge; the float accessors expose the same state without it, so the edge can be
cut later if it ever becomes awkward.

The direction that must **not** invert is `hal → gameplay`. `InputState` lives
in `hal.port` and `I_PlayerInput` in `gameplay.port` with identical accessor
names, so declaring `InputState implements I_PlayerInput` is tempting and
wrong. `gameplay.PlayerInputView` adapts between them in the correct direction.

`core` is the only package that knows about the bus, the pool, and the subsystem state machine. The subsystem ports stay minimal (`init/shutdown/port-specific-methods`); the `Subsystem` wrapper in `core.subsystem` adds the state machine and event dispatch.

---

## 3. Subsystem Specification

Each subsystem lives in its own package under `com.openfps.engine.<name>`.
Every subsystem has:
- **port/** — interfaces (the "port" side of hexagonal arch)
- **adapter/** — concrete implementations (the "adapter" side)
- The subsystem itself (state machine + event handling) is in `core/subsystem/impl/`

### 3.1 Core — `com.openfps.engine.core` — **built**

**D_ Game Loop & Engine**

- `EngineMain` — bootstrap: memory → HAL → system info → bus → registry → pool → loop
- `GameLoop` — single-threaded event producer at the configured rate (30/60/120 Hz)
- `FrameRate` — closed enum: `FPS_30`, `FPS_60`, `FPS_120` (120 is the cap)
- `GameConfig` — immutable (rate, maxTics) with factory methods
- `EventFactory` — sequence numbers + timestamps for events

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

### 3.2 Gameplay — `com.openfps.engine.gameplay` — **first-person controller done, rest stub**

**P_ Player & World Logic**

- `PlayerController` — **done.** First-person movement: yaw/pitch from look
  deltas, yaw-relative movement, pitch clamped to ±89°, wrapping yaw, eye-height
  offset, and `camera(aspect)` producing a `render.adapter.Camera`. See the
  determinism note in § 4.
- `I_PlayerInput` — the four movement channels the controller consumes
- `PlayerInputView` — adapts `hal.port.InputState` onto `I_PlayerInput`
- `PlayerState`: position (fixed-point), velocity, angle, pitch, health, inventory
- `Entity`: abstract base for all game objects (players, projectiles, pickups, doors)
- `PhysicsWorld`: collision detection, gravity, sliding along walls (BSP-assisted)
- `MapSubsector`: sector adjacency, portal handling
- `I_GameplayPort` — `tick(int)`, `loadMap(String)`, `spawnEntity(...)`, `removeEntity(...)`
- `NullGameplayPort` — stub

**Subsystem wrapper**: `GameplaySubsystem` — routes `TickEvent` to `port.tick()`, `MapLoadEvent` to `port.loadMap()`.

### 3.3 Render — `com.openfps.engine.render` — **built** (316 tests)

**R_ Multi-threaded software triangle rasterizer**

z-buffer, perspective-correct interpolation, mipmapped bilinear sampling, 32-bit colour, glTF models with baked lighting. `docs/ASSETS.md` § 2 is the canonical render target; `render/README.md` is the full Phase 5 specification — every formula, citation, and open question. Read it before writing any render code.

The earlier 2.5D design (BSP visibility, visplanes, column renderer, affine mapping, 8-bit palette, fixed 320×200) is **retired**: those were VGA-era compromises, not properties of software rendering, and they cannot draw a glTF model.

| Component | Responsibility |
|---|---|
| `Framebuffer` | Colour buffer (`int[]`, RGBA8888) + depth buffer (`float[]`, 1/w), tile geometry, `clear()` |
| `Camera` | View and projection matrices, world → clip space |
| `TriangleClipper` | Near-plane clipping in homogeneous clip space (Sutherland-Hodgman) |
| `Rasterizer` | Perspective divide, viewport transform, backface cull, edge-function setup, bounding box, binning triangles to tiles |
| `SpanRenderer` | The inner loop — perspective-correct interpolation, depth test/write, colour write |
| `TextureSampler` | Mipmapped bilinear sampling, mip level selection from UV derivatives |
| `ModelFormat` | Flat binary runtime format; the engine reads it with near-zero parsing |
| `GltfConverter` | **Build time only**, on the Gradle buildscript classpath — may use a glTF/JSON library without adding a runtime dependency (`docs/ASSETS.md` § 4) |

- `I_RenderPort` — `renderFrame(int)`
- `NullRenderPort` — stub

**Numerics**: the renderer uses `float`. Gameplay and networking stay 16.16 fixed-point — **§ 4 below is unchanged and this is not an inconsistency.** Fixed-point exists for lockstep *simulation* determinism; rendering is a pure function from game state to pixels and never feeds simulation state, so it cannot desync a peer regardless. Since JEP 306, Java 17 floating point is always-strict IEEE 754, so basic float arithmetic is bit-reproducible anyway (`Math.*` transcendentals are not; `StrictMath.*` is). Full argument: `render/README.md` § 2.

**Threading**: tiled with exclusive ownership. Triangles are binned to the tiles they touch during setup; each worker owns its tiles outright and is the only writer to those regions of the colour and depth buffers. That invariant is what makes the design lock-free — no synchronisation on the depth buffer, no false sharing between workers. Parallel work goes through the existing `WorkerPool`, never `new Thread`. The submit-and-await operation this needed (`submitParallel`, caller-participating) landed with Phase 5, along with a `DRAINING` pool state so a shutdown in flight cannot strand a tile pass.

**Presentation**: not R_'s job. The engine produces a finished framebuffer; the platform adapter uploads it. `I_WindowPort` / `I_FrameCallback` are the hook and already exist — do not design a new window port.

**BSP**: retired as the renderer's *visibility* algorithm — the z-buffer does that job now. It is **not** deleted from the project: Phase 4 keeps `BspTraverser` as a gameplay/collision structure (§ 3.2, `gameplay/README.md`).

**Open questions blocking implementation** — see `render/README.md` § 11: (a) framebuffer allocation vs. `I_MemoryPort`, (b) the role of the WAD subsystem now that art is preprocessed glTF, (c) no performance number in this design has been measured yet.

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

### 3.6 Resource — `com.openfps.engine.resource` — **built but unregistered** (101 tests)

**W_ WAD File Management**

- `WadReader` — opens `.wad`, reads lump directory
- `LumpCache` — demand-loaded, reference-counted
- `MapLumpParser` — THINGS, LINEDEFS, SECTORS, etc.
- `ImageDecoder` — DOOM-format patches and flats — **on hold, see below**
- `I_WadPort` — `open`, `close`, `readLump`, `precacheLump`, `flushCache`, `lumpCount`
- `NullWadPort` — stub

> **Open question — what is this subsystem for now?** `WadReader`, `LumpCache`,
> `MapLumpParser`, `LittleEndian` and `WadFilePort` are **built and working**
> (101 tests). But `docs/ASSETS.md` moves all art to preprocessed glTF and § 10
> records Freedoom's rejection, so the WAD path currently has **no art left to
> read**. Plausible remaining roles — map/level geometry container (the
> strongest: level layout is not art and `MapLumpParser` already reads it),
> generic asset container, or a format the project drops later — are all
> undecided. **Nothing is deleted and the subsystem is not dead.**
> `render/README.md` § 11b states the options.
>
> `ImageDecoder` is **on hold** specifically: it decodes DOOM patches and flats
> into palette indices, and § 3.3's renderer has no palette. Do not implement it
> until the question above is answered.

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
- `I_DatagramPort` — `send`, `receive`, `bind`, `close`, `processTic`, `init`, `shutdown`
- `I_FilePort` — `open`, `exists` + nested `I_FileHandle`
- `I_SystemInfoPort` (Phase 1.2) — `logicalProcessorCount`, `physicalProcessorCount`, `totalMemoryBytes`, `freeMemoryBytes`, `osName`, `osVersion`, `javaVersion`, `state()`
- `I_UserProfilePort` (Phase 1.4) — `findById`, `findAll`, `save` (upsert), `delete`, `count`, `generateNewId`, `init`, `shutdown`, `state()`

**Adapters** (in `hal/adapter/`):
- `nulladapter/` — headless stubs, used by all CI / smoke tests. Includes `MemoryUserProfilePort` (in-memory profiles).
- `sqlite/` — `SqliteAdapterFactory` + `SqliteUserProfilePort` (Xerial SQLite JDBC). Real on-disk profile persistence at `<userHome>/.openfps/profile.db`, overridable via `OPENFPS_PROFILE_DB`. Null ports for everything else.
- `desktop/` — `DesktopTimePort`, `DesktopDatagramPort` (NIO `DatagramChannel`), `DesktopAdapterFactory`

Window and input adapters live in the **`:desktop` and `:android` modules**, not
under `hal/adapter/`, because they need libGDX and `:engine` must stay
platform-free: `GdxWindowPort` / `GdxAdapterFactory` (`:desktop`, libGDX LWJGL3
backend) and `AndroidWindowPort` / `AndroidAdapterFactory` / `RoomUserProfilePort`
(`:android`, libGDX Android backend). Both implement `I_WindowPort` and drive
`I_FrameCallback`. This pair is also the presentation path for the Phase 5
software rasterizer — the engine hands over a finished framebuffer and the
adapter uploads it (§ 3.3).

**NullSystemInfoPort** returns `Runtime.availableProcessors()` (logical cores). Worker pool size = `max(1, logicalCores - 1)`, one processor being held back for the game loop thread and the platform frame loop thread. Override with `-Dopenfps.workers=N`. The rule and the evidence for it live in `ThreadPoolFactory`.

---

## 4. Data Layout Conventions

- **Fixed-point arithmetic**: `int` storing value × 65536 (1.0 = 0x10000). See `common/FixedMath.java`.
- **Angles**: degrees × 65536 in `int` (360° = 360 × 65536 = 23,592,960)
- **Coordinates**: `x`, `y`, `z` each `int` fixed-point
- **Entity ID**: `int` — unique per-level, rolled over on map load
- **Tic number**: `int` — monotonically increasing from game start, no cap
- **Frame budget**: `long` nanos, computed from `FrameRate` enum, NOT a constant

### Recorded deviation — `PlayerController` uses `float`, not 16.16

`PlayerController` holds player position, yaw and pitch in `float`. That is
simulation state, so by the letter of this section it should be fixed-point.
The deviation is deliberate and satisfies the *intent* of the rule, which is
bit-identical simulation state across peers in lockstep:

- **Since JEP 306, all Java 17 floating-point arithmetic is FP-strict IEEE 754.**
  `+ − × ÷` and `sqrt` are bit-reproducible on every conforming JVM and CPU.
  Fixed-point buys nothing here that the language does not already guarantee.
- **The real hazard is the transcendentals.** `Math.sin` / `Math.cos` are
  permitted 1–2 ulp of error and are explicitly *not* required to agree between
  implementations. `StrictMath` is fdlibm-defined and is reproducible.

So every trig call in the controller's update path is `StrictMath`, never
`Math`. This is enforced by a test that reads the compiled class's constant
pool and fails if `java/lang/Math` appears in it at all.

That guard is not belt-and-braces. A 1-ulp heading error is sub-micron per step
and invisible for minutes of play, and it **cannot reproduce in a single-process
test**, because one process is self-consistent with itself. The desync only
appears between two machines, which is exactly where it is most expensive to
diagnose. Review alone would not catch a `Math.cos` slipping in.

`FixedMath` remains correct for anything that must interoperate with the
existing fixed-point map and entity data.

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
| Desktop (Win/Linux/macOS) | Java 17 + Gradle 8.13 | Java 17 source/target; runs on JVM 17+ |
| Android | Java 17 source, compileSdk/targetSdk 36, minSdk 23 | Built — APK assembles; not yet verified on a device or emulator |
| Headless / Test | Java 17 only | null HAL adapter |

### Modules

| Module | Included when | Purpose |
|---|---|---|
| `:engine` | always | The engine. Platform-free; builds and tests with no display and no Android SDK |
| `:desktop` | always | libGDX LWJGL3 window, input, presentation, launcher |
| `:tools` | always | **Build-time only.** glTF → `ModelFormat` conversion and headless preview harnesses. Depends on `:engine` one way; nothing depends on it |
| `:android` | **only when an Android SDK is present** | libGDX Android backend. `settings.gradle.kts` checks `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `local.properties` and *silently skips* the module otherwise — so a green `gradlew build` on a machine without the SDK does **not** mean `:android` compiles. With the configuration cache on (the default), even the "skipping :android" notice is suppressed on cached runs |

### Dependencies

| Library | Version | Purpose |
|---|---|---|
| Gradle | 8.13 | Build (via wrapper) |
| SLF4J | 2.0.16 | Logging facade (industry standard) |
| Logback | 1.5.12 | Logging backend |
| Xerial SQLite JDBC | 3.46.1.0 | User profile persistence. **NOT pure Java** — bundles precompiled natives for ~20 platform triplets and is most of the ~15 MB desktop distribution. Excluded from `:android`, which uses Room instead |
| JUnit Jupiter | 5.11.4 | Testing |
| AssertJ | 3.26.3 | Test assertions |
| Checkstyle | 10.18.0 | Style enforcement (enforces STYLE.md; wired to `build`, `maxWarnings = 0`) |
| libGDX | 1.14.2 | Window, input, and framebuffer presentation. `gdx` + `gdx-backend-lwjgl3` in `:desktop`, `gdx` + `gdx-backend-android` in `:android`. **Not a `:engine` dependency** |
| Android Gradle Plugin | 8.13.2 | `:android` only, requires Gradle ≥ 8.13 and compileSdk 36 — see the version chain in `settings.gradle.kts` |
| androidx.room | 2.8.4 | `:android` only — the Android side of `I_UserProfilePort`, standing in for the excluded sqlite-jdbc |
| desugar_jdk_libs | 2.1.5 | `:android` only — core-library desugaring so Java 17 APIs resolve on minSdk 23 |
| slf4j-android | 2.0.17-0 | `:android` only. **Not optional**: logback is excluded on Android, and SLF4J 2.x with no provider binds to the NOP logger and silently discards every engine log line, errors included |
| Gson | 2.11.0 | `:tools` only — glTF JSON parsing in `GltfConverter`. Apache-2.0. Never reaches a shipped runtime classpath; `verifyToolsIsolation` enforces that |

The Gradle **wrapper is pinned at 8.13**, not 8.10: `gdx-backend-android 1.14.2 → androidx.core 1.17.0 → compileSdk 36, AGP ≥ 8.9.1 → Gradle ≥ 8.13`. None of those is a free choice.

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
- [x] 35 tests across both backends (positive, negative, random, overflow, underflow, state machine, tags)

### Phase 1.2 — Event-driven engine + multi-threaded worker pool — **done**
- [x] `I_EngineEvent` base + 6 concrete events
- [x] `SharedEventBus` (single shared queue, blocking backpressure)
- [x] `WorkerPool` (N = logicalCores − 1 hot threads, pre-started)
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

### Phase 1.5 — Desktop HAL adapter (LWGJL3) — **done**

Three of these shipped under different names than planned; the names below are
what actually exists, with the original intent noted where it diverged.

- [x] `DesktopTimePort` — monotonic time. **Uses `System.nanoTime()`, not `GLFW.glfwGetTime()`**: the port is constructed before any window exists and must work headless, which rules out a GLFW-backed clock
- [x] `GdxInputPort` + `InputAccumulator` (in `:desktop`, not `hal/adapter/desktop/`) — polls `Gdx.input` per frame rather than binding raw GLFW callbacks. Accumulates on the render thread and latches on the game-loop thread with `getAndSet(0)`
- [x] `DesktopDatagramPort` using `java.nio.channels.DatagramChannel` — datagram-only, non-blocking, one preallocated **direct** `ByteBuffer` per direction allocated at `init()` (a heap buffer makes the JDK copy through a temporary direct one on every call)
- [x] Window for render testing — `GdxWindowPort` (LWJGL3, 1280x720 default), presenting through `FramebufferPresenter`
- [x] Confirm event-driven architecture handles real input timing — the accumulate/latch split above is that confirmation

**Known seam**: `DesktopAdapterFactory` still returns `NullInputPort` / `NullWindowPort`. This is deliberate, not an oversight — the real window and input adapters need libGDX, and `:engine` must stay platform-free, so they live in `:desktop`'s `GdxAdapterFactory` instead.

### Phase 2 — WAD file loader — **partly done**
- [x] `WadReader` — header + directory parse
- [x] `LumpCache` — demand-loaded, ref-counted
- [x] `MapLumpParser` — THINGS / LINEDEFS / SECTORS / VERTEXES
- [x] `LittleEndian` + `WadFilePort` — the real `I_WadPort` (101 tests total)
- [ ] `ImageDecoder` — patch + flat decode — **on hold**, blocked on the § 3.6 open question (it decodes to palette indices; the renderer has no palette)
- [ ] `BlockmapBuilder` — pre-compute BLOCKMAP from LINEDEFS
- [ ] A `W_` subsystem registering `WadFilePort` with the `SubsystemRegistry`

### Phase 3 — Networking — **primitives built, nothing wired**
- [x] Transport decision recorded (`net/README.md` § "Transport decision") — UDP + redundant redelivery, no dependency added
- [x] `PeerConnection` (peer state — address, EWMA RTT, ack window; no socket)
- [x] `TicCmdBuffer` (lockstep) — 64x8 preallocated ring, zero per-tic allocation, decoupled from render. Note the sim rate is configurable 30/60/120, not fixed at 60
- [x] `RedundantSender` + `AckWindow` — redundant input redelivery, 64-bit ack bitfield
- [ ] `SnapshotDelta` (encode/decode)
- [ ] `Discovery` (LAN broadcast)

**These six classes are library-only.** `EngineMain` still registers `NullNetworkPort`; nothing in the running engine constructs a `PeerConnection`, `TicCmdBuffer` or `RedundantSender`, and no socket is opened. 87 tests cover them in isolation. Wiring them to `DesktopDatagramPort` is the next real Phase 3 step.

### Phase 4 — Gameplay — **planned**
- [ ] `LagCompensator` (rewind for hits) — moved from Phase 3; under pure lockstep there is nothing to rewind, so this only becomes meaningful once prediction/snapshot exists
- [ ] `PlayerState` data class
- [ ] `Entity` base + concrete types (monster, projectile, pickup, door)
- [ ] `PhysicsWorld.moveWithSlide(player, dx, dy)` — collision + slide
- [ ] `MapLoader` — read THINGS / LINEDEFS / SECTORS from WAD
- [ ] `BspTraverser` — leaf lookup, reused in both gameplay and render

### Phase 5 — Render (software triangle rasterizer) — **done, pending polish**

Full specification: `engine/src/main/java/com/openfps/engine/render/README.md`.
Render target: `docs/ASSETS.md` § 2. Ordered by dependency.

**Blockers — resolve before writing code:**
- [x] Benchmark the textured-span inner loop — done. Measured 17–21 ns/px, not 3–8; 60 Hz 1080p affords ~10–20k triangles, not 50–100k. See `docs/ASSETS.md` § 2
- [x] Decide framebuffer allocation vs. `I_MemoryPort` (`render/README.md` § 11a) — sanctioned exception, or a typed-slab capability on the port
- [x] Extend `WorkerPool` with a submit-N-jobs-and-await operation for the tile pass — `submitParallel`, caller-participating

**Implementation lanes:**
- [x] `Framebuffer` — `int[]` colour (RGBA8888, matching libGDX `Pixmap.Format.RGBA8888`), `float[]` depth (1/w), tile geometry, cache-line-padded row stride, `clear()`
- [x] `Camera` — view matrix, projection matrix (no z row needed — we store 1/w), world → clip space
- [x] `TriangleClipper` — near-plane Sutherland-Hodgman in homogeneous clip space, allocation-free 4-vertex scratch, fan re-triangulation
- [x] `Rasterizer` — perspective divide, viewport transform, backface cull, edge functions with the top-left fill rule, screen-space bounding box, per-chunk tile binning
- [x] `SpanRenderer` — reference per-pixel path. The segment path was deliberately not written: measured indistinguishable from per-pixel (`docs/ASSETS.md` § 2)
- [x] `TextureSampler` — bilinear with the −0.5 texel-centre offset, per-segment mip selection from UV derivatives
- [x] `ModelFormat` — flat binary reader, versioned header, near-zero parsing
- [x] `GltfConverter` — Gradle buildscript classpath only; triangulation, mip-chain generation, texture decode, `docs/ASSETS.md` § 5 budget enforcement
- [x] **Integration** — `SoftwareRenderPort` implements `I_RenderPort`, `GameLoop` publishes `RenderFrameEvent`, `:desktop` presents via `FramebufferPresenter`. A real Kenney model (368 tris, textured) renders to window and to PNG
- [x] **Backface winding** — measured against a no-cull oracle: `CullMode.CLOCKWISE`
- [x] Fix the drain-window defect — `WorkerPool` gained a `DRAINING` state; `submitParallel` is legal there
- [x] Default to 720p60 — `GdxWindowPort` 1280x720, `GameConfig` `FPS_60`, `DemoPreviewMain` and `AndroidLauncher` agree. Measured p50 4.9 ms at 8 workers on the 295-instance demo room
- [ ] Expose bilinear filtering as a quality toggle — **not started**. `TextureSampler` is unconditionally bilinear and no configuration field for it exists anywhere. Worth doing: bilinear alone is 2.9x the rest of the span loop, so disabling it is the cheapest path to 1080p60
- [ ] Cap the internal render resolution independently of window size — a `RenderResolution` policy class was written but never landed; it is preserved as a patch and needs re-applying against the batched pipeline

Retired from this phase and deliberately not listed: `BspTraverser` (moved to Phase 4 as a gameplay/collision structure), `WallClipper`, `VisplaneBuilder`, `ColumnRenderer`, palette blitting.

### Phase 6 — Audio — **planned**
- [ ] `SoundEngine` — voice allocation, mix loop
- [ ] `SoundEmitter` — 3D position + velocity
- [ ] `MusicPlayer` — OGG/MIDI streaming
- [ ] `PcmLoader` — read SFX from WAD lumps

---

## 8. Test Coverage Summary

**392 tests, all passing** — 354 in `:engine`, 38 in `:desktop`.

| Suite | Module | Tests | Coverage |
|---|---|---|---|
| `FixedMathTest` | engine | 6 | fixed-point arithmetic |
| `UserProfileTest` | engine | 12 | field validation, withXxx copies, equals/hashCode |
| `FrameRateTest` | engine | 9 | per-rate math, parser, rejection |
| `GameConfigTest` | engine | 10 | factories, drift correction (1000-tic sim) |
| `SharedEventBusTest` | engine | 10 | FIFO, backpressure, drain, lifecycle |
| `WorkerPoolTest` | engine | 7 | hot threads, parallel dispatch, error recovery |
| `SubsystemStateTest` | engine | 10 | transitions, error handling, thread-safety |
| `AdapterFactoryTest` | engine | 11 | HAL backend selection |
| `DesktopAdapterFactoryTest` | engine | 5 | desktop factory wiring |
| `DesktopTimePortTest` | engine | 7 | monotonic vs. wall clock |
| `DesktopDatagramPortTest` | engine | 14 | bind, send, receive, direct-buffer reuse |
| `MemoryUserProfilePortTest` | engine | 15 | in-memory CRUD, state machine |
| `SqliteUserProfilePortTest` | engine | 15 | SQLite CRUD, upsert, persistence, state machine |
| `MemoryPortTest` | engine | 35 | both backends (7 `@Nested` groups): positive, negative, random, overflow, underflow, state machine, tags |
| `TicCmdTest`, `TicCmdBufferTest`, `PeerConnectionTest`, `RedundantSenderTest`, `AckWindowTest` | engine | 87 | lockstep tic buffers, peer state, redundant redelivery, 64-bit ack bitfield |
| `LittleEndianTest` | engine | 10 | LE primitive readers |
| `WadReaderTest` | engine | 27 | header + directory parse, lump slicing |
| `LumpCacheTest` | engine | 21 | demand load, ref counting, pinning, eviction |
| `MapLumpParserTest` | engine | 16 | THINGS / LINEDEFS / SECTORS / VERTEXES |
| `WadFilePortTest` | engine | 27 | real `I_WadPort` end to end |
| `GdxWindowPortTest` | desktop | 18 | `I_WindowPort` lifecycle, frame loop, close requests |
| `GdxFrameLoopListenerTest` | desktop | 6 | `I_FrameCallback` dispatch |
| `GdxAdapterFactoryTest` | desktop | 5 | factory wiring |
| `DefaultMenuActionsTest`, `MenuButtonListenerTest` | desktop | 9 | main-menu actions |

Run with: `.\gradlew.bat test`. `.\gradlew.bat build` additionally runs
Checkstyle over main and test sources and fails on any violation.

> The `:engine` numbers above cover subsystems whose PLAN sections still read
> "stub" (net, resource). Those sections are pending their own revisions; the
> test counts here are measured, not aspirational.

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
