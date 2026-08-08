# OpenFPS — Project Plan

> **Status**: Pre-alpha — Phases 0 through 1.5 complete, plus Phase 5 (render) and Phase 7 (16-map library, **complete**: 16 of 16 maps fully implemented). Event-driven engine, unified memory, multi-threaded worker pool, configurable 30/60/120 Hz, SQLite user-profile persistence, desktop HAL with a real window and input, and a multi-threaded software triangle rasterizer driving a playable first-person match. Phase 4 (gameplay) landed ahead of its number: collision, hitscan, bots, scoring, respawn and a kill streak. Phase 6 (audio) is started and narrow — every sound synthesised at runtime. Phase 2 (WAD) is partly done and unregistered. Phase 3 (net) has a real socket and visible peers, but **match state is not replicated, so peers are not shootable**. **2715 tests** — 1764 `:engine`, 323 `:gdxshared`, 354 `:android` (both `testDebugUnitTest` and `testReleaseUnitTest` report separately), 164 `:desktop`, 110 `:tools`. **The 16-map library is complete**: 4 settings × 4 modes, 16 maps, all 4 mode logics (TDM, Hardpoint, Domination, CTF) implemented end-to-end with tests. The main menu now offers a `SELECT MAP` picker that lists every registered map and seeds the next launch (and `--map=`) from the player's choice.
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
|  N = logicalProcessorCount - 1 hot threads, pre-started        |
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

### 3.2 Gameplay — `com.openfps.engine.gameplay` — **shipping: controller, hitscan, collision and the match layer** (348 tests)

**P_ Player & World Logic**

- `PlayerController` — **done.** First-person movement: yaw/pitch from look
  deltas, yaw-relative movement, pitch clamped to ±89°, wrapping yaw, eye-height
  offset, and `camera(aspect)` producing a `render.adapter.Camera`. See the
  determinism note in § 4.
- `I_PlayerInput` — the four movement channels the controller consumes
- `PlayerInputView` — adapts `hal.port.InputState` onto `I_PlayerInput`
- `Hitscan` / `Target` / `HitResult` — **done.** Instant-hit weapons; bots shoot through the same path the player does, so one bot genuinely blocks another's shot
- `PhysicsWorld` — **done, horizontally.** Solid AABBs in a flat `float[]`; slides **x then z in that fixed order for determinism**, feeding the clipped x into the z pass so corners cannot leak. **No `floorHeightAt` yet**, so crates, the staircase and the ramp are not stand-on-able
- `Match`, `MatchState`, `MatchMode`, `MatchStatus`, `MatchSummary` — **done.** Kills, deaths, respawn, end-of-match, rematch, and the three-kill streak that arms a double-damage super blaster for `SUPER_BLASTER_TICS` (240 tics, never milliseconds)
- `Bot`, `BotPattern`, `BotSkill`, `BotShotLog` — **done.** Closed-form routes: position at tic *n* is a pure function of *n*, so bots cannot drift and a late-joining peer computes the same answer without replaying history
- `BotRng` — **done.** Seeded, **stateless**, addressed by `(seed, tic, entityId, channel)`
- `PlayerState`: position (fixed-point), velocity, angle, pitch, health, inventory — *not built; `PlayerController` holds this state directly today*
- `Entity`: abstract base for all game objects (players, projectiles, pickups, doors) — *not built*
- `MapSubsector`: sector adjacency, portal handling — *not built*
- `I_GameplayPort` — `tick(int)`, `loadMap(String)`, `spawnEntity(...)`, `removeEntity(...)`
- `NullGameplayPort` — stub

**Subsystem wrapper**: `GameplaySubsystem` — routes `TickEvent` to `port.tick()`, `MapLoadEvent` to `port.loadMap()`.

### 3.3 Render — `com.openfps.engine.render` — **built and shipping** (516 tests)

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

**Those open questions, resolved** — see `render/README.md` § 11. (a) Framebuffer allocation vs. `I_MemoryPort` is **decided**: `Framebuffer` allocates `int[]`/`float[]` directly as the one sanctioned exception to the memory-port rule, because the port hands out byte-addressed handles and the inner loop needs typed arrays the JIT can bounds-check-eliminate. (b) The WAD subsystem's role is **still open** — `resource` remains BUILT-UNWIRED with 101 tests and no art left to read. (c) Performance is now **measured, not estimated**: p50 4.9 ms at 1280×720 on 8 workers against 22.1 ms serial, bit-identical at every worker count.

**Render size is decoupled from surface size.** `RenderMode` (in `:gdxshared`) puts a ceiling on the short edge — `P480` default, `P720`, `NATIVE` — and the result is blitted up to the actual surface. A ceiling, never a target: a surface already below it renders at its own size and is never enlarged.

**Subsystem wrapper**: `RenderSubsystem` — routes `RenderFrameEvent` to `port.renderFrame()`.

### 3.4 Audio — `com.openfps.engine.audio` — **shipping, deliberately narrow** (81 tests)

**S_ Sound**

- `I_AudioPort` — six methods: play a `SoundId`, stop, clamp a volume, ask whether anything is audible
- `SoundId` — the closed set of sounds
- `NullAudioPort` — silent, counts plays (headless and test path)
- `GdxAudioPort` — in `:gdxshared`, supplied by `GdxAdapterFactory` / `AndroidAdapterFactory` via `I_AdapterFactory.getAudioPort()`
- `synth/` — `SoundBank`, `BlasterSound`, `CarbineSound`, `SuperBlasterSound`, `PowerChimeSound`, `WavAudio`

**Every sound is generated at runtime, not shipped.** The synth package writes PCM in code, so there is no audio asset in the repository, no licence question, and nothing to add to `NOTICE`. That is how the old "`docs/ASSETS.md` § 3 has no audio row" blocker was retired for the demo *without* pretending to have answered it for a real sound bank.

**What is deliberately absent**: no mixer, no positional audio, no music — and none of them half-started. `audio/README.md` keeps the 3D formulae as a reading list, explicitly not as a spec this code is failing to meet. Everything degrades: no device, headless JVM, and an unwritable cache each log once and keep playing.

**Subsystem wrapper**: `AudioSubsystem` — registered as `S_`, port supplied by the adapter factory rather than hard-coded null.

### 3.5 Network — `com.openfps.engine.net` — **partial: transport ships, match state does not** (129 tests here, 13 more in `demo/RemotePlayersTest`)

**G_ Peer-to-Peer Networking**

`NetSession` opens a real socket over `DesktopDatagramPort`; `TicCmdEncoder` does the float-to-wire quantisation; `demo/RemotePlayers` replays each peer's commands through its own `PlayerController` on the shared `PhysicsWorld`, so two processes draw each other's bodies. Measured live: 77 KB exchanged, zero malformed packets, zero strangers.

**Two processes on one machine, today**: `gradlew :desktop:installDist`, then launch two plain JVMs from the image with `--net=<id>:<port> --peer=<id>@<host>:<port>`. WSL2 with `apt install openjdk-21-jdk` works as the second peer (reach the host via the WSL gateway IP, e.g. `172.30.192.1`, not `127.0.0.1` — WSL2 has its own loopback). Full assessment, including the four-step cost to go from "peers see each other walk" to "two real machines play a real TDM match": [`docs/map-selection-and-network-report.md`](docs/map-selection-and-network-report.md).

**Still open, and the next real work**: `Match` has never heard of a remote player, so peers are visible but **not shootable**, respawn is not replicated (a teleport driven by `Match`, not by an input), and a late join costs a constant 17.07-unit offset. Desync detection is deliberately not started — the 20-byte header has no discriminator for a second packet type, and until match state is replicated the simulations are known to differ, so it would fire constantly and tell nobody anything.

- `PeerConnection` — per-peer state (address, RTT, ack window, loss stats)
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

### Phase 3 — Networking — **transport wired and measured; match state not replicated**
- [x] Transport decision recorded (`net/README.md` § "Transport decision") — UDP + redundant redelivery, no dependency added
- [x] `PeerConnection` (peer state — address, EWMA RTT, ack window)
- [x] `TicCmdBuffer` (lockstep) — 64x8 preallocated ring, zero per-tic allocation, decoupled from render. Note the sim rate is configurable 30/60/120, not fixed at 60
- [x] `RedundantSender` + `AckWindow` — redundant input redelivery, 64-bit ack bitfield
- [x] `TicCmdEncoder` — float-to-wire quantisation
- [x] `NetSession` — a real socket over `DesktopDatagramPort`, attached by `DesktopLauncher`
- [x] `demo/RemotePlayers` — replays a peer's commands through its own `PlayerController` on the shared `PhysicsWorld`, so peers appear as bodies
- [x] Two live processes: `.\run-desktop.ps1 -TwoPeers -StartInGame`, distinct ports (5021/5022) and profile DBs
- [ ] **Replicate match state** — `Match` has never heard of a remote player. This is the next real step and it is the root cause of all three items below
- [ ] Peers are visible but **not shootable**
- [ ] Respawn is not replicated (a teleport driven by `Match`, not by an input)
- [ ] A late join costs a constant 17.07-unit offset (4 missing leading tics × 4.267 units)
- [ ] Desync detection (§ 10's periodic `stateHash`) — **deliberately not started.** Needs a second packet type and the shipped 20-byte header has no discriminator; until match state is replicated the simulations are known to differ, so it would fire on every comparison
- [ ] `SnapshotDelta` (encode/decode)
- [ ] `Discovery` (LAN broadcast)

**Measured, not asserted**: a loopback test over two real UDP sockets, and two live game processes that exchanged 77 KB with zero malformed packets and zero strangers. Do not read this as "multiplayer works" — a session that exchanges packets perfectly and cannot shoot the other player is exactly where this is.

### Phase 4 — Gameplay — **the match layer landed early; the WAD-backed world did not**
- [x] `PhysicsWorld` horizontal collision + slide — x then z in a fixed order, corner-safe
- [x] `Hitscan` / `Target` / `HitResult`, shared by the player and the bots
- [x] `Match` + `Bot` + `BotRng` — kills, deaths, respawn, scoring, rematch, kill streak
- [ ] `PhysicsWorld.floorHeightAt(...)` — **the next gameplay step.** Collision is horizontal only, so the crates the jump was tuned to clear are not stand-on-able. One addition unlocks crates, staircase and ramp together
- [ ] `LagCompensator` (rewind for hits) — moved from Phase 3; under pure lockstep there is nothing to rewind, so this only becomes meaningful once prediction/snapshot exists
- [ ] `PlayerState` data class
- [ ] `Entity` base + concrete types (monster, projectile, pickup, door)
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

### Phase 6 — Audio — **started, deliberately narrow**
- [x] `I_AudioPort` + `SoundId` + `NullAudioPort`, registered as `S_`, port supplied by `I_AdapterFactory.getAudioPort()`
- [x] `GdxAudioPort` in `:gdxshared`, wired by both launchers
- [x] `synth/` — `SoundBank` plus `BlasterSound`, `CarbineSound`, `SuperBlasterSound`, `PowerChimeSound`, `WavAudio`. **Every sound is generated at runtime**, so the repository ships no audio asset and `NOTICE` needs no entry
- [x] Voice limiting; graceful degradation on no device, headless JVM, unwritable cache
- [ ] `SoundEngine` — voice allocation, mix loop
- [ ] `SoundEmitter` — 3D position + velocity
- [ ] `MusicPlayer` — OGG/MIDI streaming
- [ ] `PcmLoader` — read SFX from WAD lumps

The four unchecked items are **deliberately not half-started**. `audio/README.md` keeps the 3D formulae as a reading list, explicitly not as a spec this code is failing to meet.

### Phase 7 — 16-map multiplayer library — **Pass 7 of 7 done; the 16-map grid is complete**

The library is a 4-by-4 grid: four settings (Urban Warzone, Industrial Complex,
Desert Ravine, Arctic Station) crossed with four multiplayer modes (TDM,
Hardpoint, Domination, CTF). The implementation is sequenced over four
passes — one setting per pass, TDM first because it is the only mode
without per-tic state, then Hardpoint, Domination, CTF.

**Pass 1 — Urban Warzone × TDM (Cornerstone), the architecture.** Done.

- [x] `MatchMode` extended with `TDM`, `HARDPOINT`, `DOMINATION`, `CTF` siblings of the legacy two
- [x] `MapSpec` data class — final, immutable, equal-by-id. Lanes, spawns, waypoints, mode-specific markers, asset paths
- [x] `MapLibrary` registry (singleton, static-initialised, thread-safe) and `MapLoader` (delegates to library)
- [x] `Match` extension — spec-aware constructors, `mode()` and `teamScores()` accessors, per-mode `updateMode(ticIndex, ...)` dispatch with the four modes each having a stub method that runs without error
- [x] **`cornerstone` map** — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildCornerstoneMap` and committed as a deliberate small fixture
- [x] `EngineMain --map=<id>` CLI arg + `MapSmokeGameplayPort` headless test path
- [x] Design specs for `hp-overpass`, `dom-tripoint`, `ctf-extraction` — committed at `docs/maps/urban-warzone/`, all marked **DESIGN ONLY**
- [x] `CornerstoneMapBuilder` (`:tools`) — procedural level model, 264 triangles, 264 generated, 60 mip textures
- [x] Tests: `MapSpecTest`, `MapLibraryTest`, `MatchModeTest`, `MatchMapSpecTest`
- [x] **Pass 2 — Urban Warzone × HP / Dom / CTF + Cornerstone asset swap + MapScene for the windowed render path.** Done.
  - [x] **`refinery` map** — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=refinery --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildRefineryMap` and committed (564 triangles, 1128 vertices, 2 textures)
  - [x] **Hardpoint mode logic** — `Match.updateHardpoint(...)` implements resolve / score / advance. `Bot.team()` (a new field), `Match.setPlayerTeam(...)`, per-team `hardpointRedScore` / `hardpointBlueScore`, `hardpointActiveHolder`, `hardpointActiveZoneIndex` accessors. `teamScores()` returns the Hardpoint scores
  - [x] `RefineryMapBuilder` (`:tools`) — procedural level model with three distillation columns, a process hall, internal pipework, three boilers, four corner stairways, and a mid-level catwalk ring
  - [x] Design specs for `hp-foundry`, `dom-pipeline`, `ctf-storage` — committed at `docs/maps/industrial-complex/`, all marked **DESIGN ONLY** (HP is **MODE READY** — the spec doc notes the mode logic is shipped but the map is not)
  - [x] Tests: `MatchHardpointTest` (13 tests covering resolve, scoring, rotation, team scores, reset)
- [ ] **Pass 3 — Industrial Complex × TDM + Hardpoint mode.**
  - [x] **`crossroads` map** — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=crossroads --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildCrossroadsMap` and committed (444 triangles, 888 vertices, 2 textures). Small desert town at a four-way crossroads: shack row (N), central plaza (M), warehouse row (S), with two wash channels and four corner plaza walls as named chokepoints
  - [x] **Domination mode logic** — `Match.updateDomination(...)` implements resolve / capture / score. Three flag owners (length-3 `Team[]`, init NEUTRAL, validated by `MapSpec` as exactly 3), per-tick scoring (1 per flag held), capture rules (contested/empty leave owner unchanged), per-team `dominationRedScore` / `dominationBlueScore`, `dominationFlagOwner(flagIndex)` accessor, `isInDominationRadius(...)` helper. `teamScores()` returns the Domination scores
  - [x] `CrossroadsMapBuilder` (`:tools`) — procedural level model: 3-lane desert layout (shack/plaza/warehouse), 2 wash channel walls, 4 corner plaza walls, 2 wooden wells, 2 cactus pairs. Mirrors `CornerstoneMapBuilder` / `RefineryMapBuilder` style
  - [x] Design specs for `hp-mesa`, `dom-sandbar`, `ctf-stronghold` — committed at `docs/maps/desert-ravine/`, all marked **DESIGN ONLY** (HP and Dom are **MODE READY** — the spec docs note the mode logic is shipped but the map is not)
  - [x] Tests: `MatchDominationTest` (14 tests covering resolve, capture state, contested/empty rules, per-flag scoring, team scores, reset)
- [x] **Pass 4 — Desert Ravine × TDM + Domination mode.**
  - [x] **`arctic-station` map** — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=arctic-station --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildArcticStationMap` and committed (300 triangles, 600 vertices, 2 textures). Polar rest stop with two long east-west frozen bridges over a frozen ravine, fuel depots on the North Bridge, a service building anchoring the south, and snowdrift cover on the ravine floor
  - [x] **CTF mode logic** — `Match.updateCtf(...)` implements pickup / return / capture / drop-on-death. Two flag carriers (`ctfRedFlagCarrier` / `ctfBlueFlagCarrier`, `null` = at home, otherwise the team carrying it), per-team `ctfRedCaptures` / `ctfBlueCaptures`, `ctfElapsedTics` for the time limit, `CTF_CAPTURE_LIMIT = 5` / `CTF_TIME_LIMIT_TICS = 36000` constants, `ctfRedCaptures()` / `ctfBlueCaptures()` / `ctfRedFlagCarrier()` / `ctfBlueFlagCarrier()` accessors. `teamScores()` returns the per-team CTF capture counts; `state()` returns `WON` at the capture or time limit
  - [x] `ArcticStationMapBuilder` (`:tools`) — procedural level model: 2 long bridges (each a deck + underside pair), 2 fuel depots, 1 service building + canopy, 4 snowdrift walls, 4 bridge supports, 4 rocks, perimeter walls
  - [x] Design specs for `hp-subzero`, `dom-frostline`, `ctf-coldfront` — committed at `docs/maps/arctic-station/`, all marked **MODE READY** (HP, Dom, and CTF logic are all shipped; the maps are not built — future passes)
  - [x] Tests: `MatchCtfTest` (16 tests covering initial state, pickup, return/capture, drop on death, scoring, team scores accessor, capture limit, reset)
- [x] **Pass 5 — Non-Urban × Hardpoint + Kenney-ize the 3 procedural TDM maps.**
  - [x] **`foundry` map** (Industrial Complex × Hardpoint) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=foundry --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildFoundryMap` and committed (552 triangles, 1104 vertices, 3 textures). 320×320 heavy-machinery foundry with three machine halls (cast-metal shop at z=270, assembly floor at z=160, cooling room at z=40), a vertical gantry at x=0, two horizontal gantries at y=64 (the casting-gantry at z=80 and the foundry spine at z=160), four corner stairways, eight crates, and a Kenney-textured palette
  - [x] **`mesa` map** (Desert Ravine × Hardpoint) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=mesa --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildMesaMap` and committed (672 triangles, 1344 vertices, 3 textures). 320×320 desert plateau with a raised mesa top (y=32) covering the centre, a low-roofed cave at the south end (HP_A), a 16% grade south ramp, an eight-tread north switchback, four corner rocks, two cactus pairs, two wash channels, four perimeter walls
  - [x] **`arctic-hp` map (Subzero)** (Arctic Station × Hardpoint) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=arctic-hp --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildSubzeroMap` and committed (312 triangles, 624 vertices, 3 textures). 320×320 polar research outpost with three small sheet-metal buildings (the Generator Shed at z=64, the Operations Trailer at z=160, the Fuel Depot at z=256) connected by two snow-walled trenches (the W trench and the E trench)
  - [x] **`refinery` / `crossroads` / `arctic-station` texture swap** — the three TDM builders got a `--atlas=<colormap.png>` CLI flag mirroring the `CornerstoneMapBuilder` Pass 2 pattern. The level `.ofm` files were rebuilt from the Kenney Prototype Kit's `colormap.png` (CC0); the geometry stayed the same, only the textures changed. The build tasks now accept `-PrefineryAtlas=` / `-PcrossroadsAtlas=` / `-ParcticStationAtlas=`. **Pass 5 also migrated the asset paths in `Maps.java` for the three TDM maps from the pre-Pass 2 `assets/maps/<id>/level.ofm` form to the post-Pass 2 `engine/src/main/resources/maps/<id>/level.ofm` form, where the actual `.ofm` files have always lived — the pre-Pass 2 form was a Pass 2 drift the smoke test had not surfaced.**
  - [x] `FoundryMapBuilder` / `MesaMapBuilder` / `SubzeroMapBuilder` (`:tools`) — three new procedural level builders. All three accept `--atlas=<colormap.png>` for Kenney-textured builds, with the pre-Pass 5 procedural fallback as the default
  - [x] Tests: `MapLibraryTest` (+6 tests pinning the registration, mode/setting/lane-count, spawn-point-count, waypoint non-emptiness, and zone-id uniqueness for each new HP map)
- [x] **Pass 6 — Non-Urban × Domination.** The Domination mode logic was already implemented in Pass 3 (`Match.updateDomination` + 13 `MatchDominationTest` cases); Pass 6 only adds the three new map instances, level models, factory methods, and MapLibraryTest entries. No `Match` / `MapMarkers` / `Maps` API changes.
  - [x] **`pipeline` map** (Industrial Complex × Domination) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=pipeline --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildPipelineMap` and committed (444 triangles, 888 vertices, 3 textures). 320×320 pipeline pumping station with three long east-west pipelines at z=64, z=160, z=256, three control valves at the centres, three north-south catwalks at y=64, two east-west underpasses at x=±100, four perimeter walls, eight cover crates, and a Kenney-textured palette
  - [x] **`sandbar` map** (Desert Ravine × Domination) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=sandbar --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildSandbarMap` and committed (552 triangles, 1104 vertices, 3 textures). 320×320 wide, shallow canyon with three flat-topped sandstone buttes at z=64, z=160, z=256 (each 96×32×96 with one 8-tread east-side ramp), a central dry riverbed (y=-8), two wash channels, four corner rocks, two cactus pairs, four perimeter walls, and a Kenney-textured palette
  - [x] **`arctic-dom` map (Frostline)** (Arctic Station × Domination) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=arctic-dom --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildArcticDomMap` and committed (396 triangles, 792 vertices, 3 textures). 320×320 polar ice road with three flag platforms at z=80, z=160, z=240 (each 16×16×16 with a 4-tread ramp and a 32-tall radar mast on top), a central east-west ice road (x=144..176, z=80..240), two 16-tall snow walls at z=64 and z=256 with 32-wide underpass gaps at x=±100, four perimeter walls, and a Kenney-textured palette. **The map id is `arctic-dom`; the display name is "Frostline" (per the design spec)**
  - [x] `PipelineMapBuilder` / `SandbarMapBuilder` / `ArcticDomMapBuilder` (`:tools`) — three new procedural level builders. All three accept `--atlas=<colormap.png>` for Kenney-textured builds, with the pre-Pass 6 procedural fallback as the default
  - [x] Tests: `MapLibraryTest` (+6 tests pinning the registration, mode/setting/lane-count, spawn-point-count, waypoint non-emptiness, and flag-id uniqueness for each new Domination map; the `arctic-dom` test also pins the `displayName() == "Frostline"` invariant so a future rename is intentional)
- [x] **Pass 7 — Non-Urban × CTF.** The CTF mode logic was already implemented in Pass 4 (`Match.updateCtf` + 16 `MatchCtfTest` cases); Pass 7 only adds the three new map instances, level models, factory methods, and MapLibraryTest entries. No `Match` / `MapMarkers` / `Maps` API changes.
  - [x] **`storage` map** (Industrial Complex × CTF) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=storage --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildStorageMap` and committed (192 triangles, 384 vertices, 2 textures). 320×320 chemical storage facility with two warehouse buildings (one per team) at the south-west and north-east corners, eight storage tanks arranged in two rows of four in the centre (the maze), and a single north-south catwalk at x=0, y=64
  - [x] **`stronghold` map** (Desert Ravine × CTF) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=stronghold --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildStrongholdMap` and committed (168 triangles, 336 vertices, 2 textures). 320×320 sandstone fortress with two gate towers (east and west), four corner towers (16×16×32 each), a 96×96 central courtyard, a 16×16×8 fountain at the courtyard centre, and two flanking cliff walls (north and south) at y=32
  - [x] **`coldfront` map** (Arctic Station × CTF) — fully implemented, runnable through `.\gradlew.bat :engine:run --args="--headless --map=coldfront --fps=60"`. Level `.ofm` is procedurally generated by `:tools:buildColdfrontMap` and committed (132 triangles, 264 vertices, 2 textures). 320×320 polar-research base split across two sides of a frozen river — RED base on the west bank (32×32 main hut at (-128, 160) + 16×16×48 watchtower at (-32, 160) + 24×24×16 service shed at (-64, 120)) and BLUE base on the east bank (mirror)
  - [x] `StorageMapBuilder` / `StrongholdMapBuilder` / `ColdfrontMapBuilder` (`:tools`) — three new procedural level builders. Snow-tone / sandstone / sheet-metal palettes matching each map's setting
  - [x] Tests: `MapLibraryTest` (+6 tests pinning the registration, mode/setting/lane-count, spawn-point-count, waypoint non-emptiness, and 2-bases with positive radii for each new CTF map)

The three per-mode implementations are intentionally stubbed in Pass 1 —
the mode enum, the per-mode dispatch and the marker subtypes are all real,
but the actual capture / rotation / pickup logic is left for the
mode-specific pass. A future pass plugs each mode's rules into the
`updateHardpoint` / `updateDomination` / `updateCtf` stubs without changing
any other class.

---

## 8. Test Coverage Summary

**2471 tests, all passing** — 1746 `:engine`, 300 `:gdxshared`, 177 `:android`,
164 `:desktop`, 84 `:tools`.

**The per-package breakdown lives in `README.md` § Test Coverage and nowhere
else.** This section used to carry a per-suite table of its own; it drifted to
392 while the real figure was six times that, because two tables of the same
numbers only ever agree on the day they are written. Each package's own
`## Status` block carries its count, and those sit next to the code.

Counting rule, because three plausible methods give three different answers:
sum the `tests=` attribute of every `<testsuite>` element Gradle wrote under
`<module>/build/test-results/`, and count `:android` **once**. Its suite runs
against a debug and a release variant, so a raw tally reports 354. Counting XML
files instead undercounts nothing but splits `@Nested` groups; counting `@Test`
annotations in source misses parameterized and repeated tests.

Run with `.\gradlew.bat test`. `.\gradlew.bat build` additionally runs
Checkstyle over main and test sources and fails on any violation
(`maxWarnings = 0`).

Two properties are enforced by tests rather than by convention, and both are
load-bearing rather than nice-to-have:

- **Renderer determinism** — the demo shots are rendered at every worker count
  from serial through 16 and compared byte-for-byte. This is the safety net for
  culling and worker-count changes.
- **Simulation determinism** — `PlayerController` is checked at the
  constant-pool level to prove it never references `java/lang/Math`, only
  `StrictMath`. Lockstep needs transcendentals to agree bit-for-bit across
  machines, and only `StrictMath` guarantees that.

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



