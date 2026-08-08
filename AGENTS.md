# AGENTS.md â€” OpenFPS Engine Agent Instructions

This file provides project-specific context and conventions for AI coding agents
working in this repository. It supplements, not replaces, the agent's built-in
persona (Mavis) and the global system prompt.

---

## Project Summary

**Name**: OpenFPS
**Type**: Game engine (FPS, peer-to-peer networking, JVM)
**Language**: Java 17 (source/target), running on JVM 17+
**Build**: Gradle 8.x (Kotlin DSL)
**License**: MIT

---

## Critical Conventions

### Use the Services and Constants We Already Have
**This is the #1 rule.** Before writing new code, check whether the
service or constant already exists. If it does, USE IT. Do not
reimplement.

Full list in `STYLE.md` Â§ 13. Short version:

- All allocation â†’ `I_MemoryPort` (via `MemoryPortFactory`)
- All inter-component comms â†’ `I_EventBusPort` (via `EventBusFactory`)
- All parallel work â†’ `WorkerPool` (via `ThreadPoolFactory`)
- All time reads â†’ `I_TimePort` (never `System.nanoTime()`)
- All system info â†’ `I_SystemInfoPort`
- All user profile reads/writes â†’ `I_UserProfilePort` (never touch SQLite or Room directly)
- All fixed-point math â†’ `FixedMath`
- All config (rate, maxTics) â†’ `GameConfig` / `FrameRate`
- All subsystem classes â†’ extend `Subsystem` (don't implement `Runnable`)
- All primitive constants â†’ `Constants` (don't redeclare magic numbers)
- All logging â†’ SLF4J (don't use `System.out`, `System.Logger`, etc.)
- All CORE-targeted events â†’ `CoreSubsystem` (every `SubsystemId` needs an owner)

Anti-patterns in `STYLE.md` Â§ 13.4 are instant review failures.

### Immutability-First
- All instance fields MUST be `final`
- All method parameters MUST be `final`
- Local variables SHOULD be `final` unless reassigned
- Prefer primitives over boxed types everywhere

### Control Flow
- **No ternary `?:`** â€” use `if`/`else`, an early return, or a `switch`
  (`STYLE.md` Â§ 5.5). Switch *expressions* (`case X -> value`) are fine.
- **No nested lambdas** â€” one level is the hard limit; a lambda
  containing another `->` is a review failure (`STYLE.md` Â§ 6.2).
  Single-operation lambdas and method references are fine.

### Brace Style
- K&R variant: **braces on their own lines**
  ```java
  public void foo()
  {
      if (condition)
      {
          doThing();
      }
  }
  ```

### Adapter/Port Architecture
- `port/` packages contain interfaces only
- `adapter/` packages contain implementations
- Core engine NEVER imports from an `adapter/` package
- Platform-specific code lives only in `hal.adapter.*`

### Naming
- Follow standard Java conventions (PascalCase classes, camelCase methods/fields)
- Engine prefix signals: `D_*` core, `P_*` gameplay, `R_*` render, `S_*` audio, `G_*` net, `W_*` resource, `Z_*` memory, `I_*` HAL port
- Static final constants: `SCREAMING_SNAKE_CASE`

### Documentation
- Every non-private method: Javadoc at method beginning
- Complex logic blocks: inline `//` comments
- `PLAN.md` section 7 is the living roadmap; update it when implementing modules
- `STYLE.md` is the authoritative style guide

---

## File Locations

| What | Where |
|---|---|
| Project plan | `PLAN.md` |
| Style guide | `STYLE.md` |
| Build instructions | `BUILD.md` |
| Checkstyle config | `config/checkstyle/checkstyle.xml` |
| Asset & render-target policy | `docs/ASSETS.md` |
| Engine source | `engine/src/main/java/com/openfps/engine/` |
| Engine tests | `engine/src/test/java/com/openfps/engine/` |
| Desktop launcher (libGDX LWJGL3) | `desktop/src/main/java/com/openfps/desktop/` |
| Android launcher (libGDX Android) | `android/src/main/java/com/openfps/android/` |
| Shared libGDX code (no backend) | `gdxshared/src/main/java/com/openfps/gdx/` |
| Allocation audit | `docs/MEMORY.md` |
| WebAssembly port assessment | `docs/WASM.md` |
| Run scripts (always rebuild) | `run-desktop.ps1`, `run-android.ps1`, `.vscode/tasks.json` |

The project is a **five-module Gradle build**: `:engine` (pure Java 17, no
platform dependencies, builds and tests headless anywhere), `:gdxshared`
(libGDX **core** only, no backend), `:desktop`, `:tools` (build-time only), and
`:android`. `:android` is only included when an Android SDK is present â€” see
the guard in `settings.gradle.kts`. Paths were flat `src/main/java/...` before
the module split; anything still saying that is stale.

---

## Build Commands

```powershell
# Build (desktop)
.\gradlew build

# Run tests only
.\gradlew test

# Checkstyle only
.\gradlew checkstyleMain

# Run the engine headless (2s smoke run, then clean shutdown).
# NAME THE MODULE. Both :engine and :desktop apply the `application` plugin,
# so a bare `.\gradlew run` resolves to TWO tasks and launches both.
.\gradlew :engine:run
.\gradlew :engine:run --args="--fps=120 --headless"

# Run the windowed desktop client
.\gradlew :desktop:run

# Preferred: the run scripts rebuild every time and print the commit they are
# running before anything opens, so a window is never ambiguous about which
# build produced it. This is how you check a change actually landed.
.\run-desktop.ps1 -StartInGame -DebugOverlay
.\run-desktop.ps1 -TwoPeers -StartInGame     # two peers, distinct ports + profile DBs
.\run-android.ps1 -RenderMode 480p

# Android: there is NO -Pandroid flag. :android is included automatically
# when ANDROID_HOME / ANDROID_SDK_ROOT / local.properties is present.
.\gradlew build
```

`JAVA_HOME` must point at a JDK 17+. `Unrecognized VM option 'UseZGC'` means
it is pointing at an old JDK â€” see `BUILD.md`.

---

## Common Patterns

### Port Interface Pattern
```java
package com.openfps.engine.<subsystem>.port;

/**
 * Port interface for <subsystem>.
 * Implemented by platform adapters; called by core engine.
 */
public interface I_<Capability>Port
{
    void init();
    void shutdown();
    Result execute(final Input in);
}
```

### Immutable Data Record
```java
public final class PlayerState
{
    private final int health;
    private final int armor;
    private final long x;  // fixed-point

    public PlayerState(final int health, final int armor, final long x)
    {
        this.health = health;
        this.armor = armor;
        this.x = x;
    }

    public int health()   { return health; }
    public int armor()    { return armor; }
    public long x()       { return x; }
}
```

### Zone Allocator Usage
```java
private final I_MemoryPort memory = MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE);
memory.init(Constants.ZONE_HEAP_SIZE);

public void spawnEntity(final int entityType)
{
    final int handle = memory.allocate(128, I_MemoryPort.TAG_GAME);
    if (handle == I_MemoryPort.NULL_HANDLE)
    {
        // OOM â€” never silent
    }
    // ... use handle, then memory.free(handle) when done
}
```

---

## What Not To Do

- Do NOT add external libraries without discussion
- Do NOT create `public static void main()` in non-core packages
- Do NOT write to `System.out` / `System.err` in production code â€” use **SLF4J** (`LoggerFactory.getLogger`)
- Do NOT use `System.Logger` or `java.util.logging` â€” they're banned in favor of SLF4J
- Do NOT import `java.util.List<Integer>` or any boxed collection in hot paths
- Do NOT add Android-specific code outside the `:android` module. (There is no
  `hal.adapter.mobile` package and there is not going to be one: an Android
  adapter needs the Android SDK, and `:engine` must stay buildable without it.
  `:android` owns `AndroidAdapterFactory` and everything under it.)
- Do NOT skip updating `PLAN.md` section 7 when completing a roadmap item
- Do NOT `new byte[]` outside a memory port adapter. (**One sanctioned
  exception, already decided â€” not an open question.** `Framebuffer` allocates
  its `int[]` colour and `float[]` depth arrays directly, because the port hands
  out byte-addressed handles and the rasterizer needs typed arrays the JIT can
  bounds-check-eliminate. The reasoning is recorded in `render/README.md` Â§ 11a
  and `STYLE.md`. It covers `Framebuffer` and nothing else; a new caller wanting
  the same exemption needs the same argument made again.)
- Do NOT `System.nanoTime()` / `System.currentTimeMillis()` in engine code â€” use
  `I_TimePort` (`nanos()`/`millis()` monotonic, `epochMillis()` wall clock).
  Sanctioned exceptions: the time-port adapters, and shutdown timeouts that
  never feed simulation state.
- Do NOT `new Thread(...)` for event handling â€” use `WorkerPool`. The game loop
  is the exception: it is the producer, so it gets its own dedicated thread
  (`openfps-gameloop`). It cannot run on the pool it feeds (deadlock at
  `workerCount == 1`), and it cannot run on main either â€” **the main thread is
  reserved for the platform event pump**, because GLFW requires window calls
  and `glfwPollEvents()` there.
- Do NOT add magic numbers â€” use `Constants` or `FrameRate`
- Do NOT implement `Runnable` for a subsystem â€” extend `Subsystem`
- Do NOT instantiate `JvmMemoryPort` / `SharedEventBus` / `WorkerPool` / `SqliteUserProfilePort` directly â€” use the factories
- Do NOT add a new frame rate value â€” extend the `FrameRate` enum
- Do NOT touch SQLite or Room directly â€” go through `I_UserProfilePort`
- Do NOT skip writing tests for new code
- Do NOT use a ternary `?:` â€” use `if`/`else` or `switch` (`STYLE.md` Â§ 5.5)
- Do NOT nest a lambda inside another lambda (`STYLE.md` Â§ 6.2)
- Do NOT skip the documentation-to-code map in `STYLE.md` Â§ 11 when adding a new service

---

## Subsystem Owners (Living)

> **Every package README opens with a `## Status` block. Read that first â€”
> it is the authoritative, dated statement of what is built, what is not, what
> is blocked, and the single next actionable step.** The table below is a
> one-line index into those blocks, not a replacement for them. If the two ever
> disagree, the package README wins, because it sits next to the code.
>
> **If you change a subsystem, update its `## Status` block in the same commit.**
> That block is the handoff to the next agent. A stale one is worse than none:
> this project has already lost time to a table that called the renderer
> "nothing implemented" while it had 316 passing tests.
>
> The `State` field is a closed set â€” `SHIPPING` (built, wired, in use),
> `BUILT-UNWIRED` (tested but nothing calls it), `PARTIAL` (some of the named
> surface exists), `STUB` (port + null adapter only), `NOT STARTED`. Keep to
> those five so the blocks stay greppable.
>
> `BUILT-UNWIRED` is not a synonym for "done". `resource` is in that state and
> holds 101 tests that no running code exercises. `net` left it â€” a socket is
> opened now, and two live processes have been measured exchanging tics.

All paths below are under `engine/src/main/java/com/openfps/engine/` unless a
module is named. `:desktop`, `:tools` and `:android` carry the same block in
their module-root `README.md`.

| Subsystem | Package | Status |
|---|---|---|
| Core Loop | `core` | Phase 1.3 â€” event-driven, multi-threaded, configurable 30/60/120 Hz; pool also does caller-participating parallel fan-out (`submitParallel`) |
| Gameplay | `gameplay` | **SHIPPING**, registered as `P_`. `PlayerController` (first-person movement + gravity and a jump, StrictMath-deterministic), `Hitscan` / `Target` / `HitResult`, `PhysicsWorld`, and the match layer: `Bot`, `BotPattern`, `BotRng`, `BotSkill`, `BotShotLog`, `Match`, `MatchState`, `MatchMode`, `MatchStatus`, `MatchSummary`, `MapSpec`, `MapScene`, `MapLibrary`, `MapLoader`, `MapMarkers` (sealed: TDM / Hardpoint / Domination / CTF), `MapSetting`, `Team`, `Lane`, `SpawnPoint`, `Waypoint`, `MapAssets`. `MatchMode` carries the four real multiplayer modes â€” `TDM`, `HARDPOINT`, `DOMINATION`, `CTF` â€” alongside the legacy `SINGLE_PLAYER` / `MULTIPLAYER`; `Match` dispatches per-tic mode updates, with full rule implementations for all three objective modes (hardpoint zone rotation + capture, domination flag capture + ownership, CTF pickup / drop-on-death / return / capture). `MapLibrary` is a singleton registry, lock-free reads, defaults register at class load time; `MapSpec` is final, immutable, equal-by-id. **The four Urban Warzone maps are fully implemented** (Pass 1 + 2) â€” `cornerstone` (TDM), `overpass` (Hardpoint), `tripoint` (Domination), `extraction` (CTF) â€” plus three TDM maps in the other settings (`refinery`, `crossroads`, `arctic-station`). `MapScene` (Pass 2) wraps a spec's `level.ofm` into a `Scene` and is wired into the desktop launcher's `--map=<id>` path; the demo is bypassed in map mode and the per-tic simulation runs against the `MapSmokeGameplayPort`. See `docs/maps/README.md` for the grid. `PhysicsWorld` holds solid AABBs in a flat `float[]` and slides **x then z, in that fixed order for determinism**, feeding the clipped x into the z pass so corners do not leak; it is **horizontal only**, so crates, the staircase and the ramp are not stand-on-able until a `floorHeightAt` lands. `BotRng` is seeded, **stateless**, and addressed by `(seed, tic, entityId, channel)` so a late joiner computes the same answer without replaying history. Bots walk closed-form routes â€” position at tic *n* is a pure function of *n*, so they cannot drift and a late-joining peer computes the same answer without replaying history â€” and shoot back through the same `Hitscan` the player's own weapon uses, so one bot genuinely blocks another's shot. **Three kills without dying** arm a super blaster for `SUPER_BLASTER_TICS` (240 tics, never milliseconds) at exactly twice `PLAYER_SHOT_DAMAGE`; a death takes both the streak and a live buff, `reset()` clears both, and a kill while it is live neither extends nor refreshes the window |
| Render | `render` | **Built and shipping** (516 tests), registered as `R_`. Multi-threaded software triangle rasterizer: `Framebuffer`, `Camera`, `TriangleClipper`, `Rasterizer`, `SpanRenderer`, `TextureSampler`, `MipChain`, `ModelFormat`, `Scene`, `Rgba`, `OutlinePass`, `SoftwareRenderPort`. A frame is world instances â†’ `OutlinePass` (only if the scene has tagged entities; it needs the finished id buffer) â†’ translucent instances sorted back-to-front, depth-tested and never depth-written â†’ clear depth, not colour â†’ the view-space viewmodel. Measured p50 4.9 ms at 1280x720 on 8 workers, and **bit-identical at every worker count** â€” that invariant is the safety net for culling and worker-count changes, so a change that breaks it is wrong even if it looks right. **Known hole: the translucent phase has no pooled-equals-serial assertion** (`SoftwareRenderPortTranslucentTest` renders serially only), which is precisely where a blend's read-modify-write makes tile ownership load-bearing â€” see `render/README.md` Â§ Status. Render size is decoupled from surface size by `RenderMode` in `:gdxshared` (P480 default / P720 / NATIVE, a ceiling on the short edge, never upscaling). `docs/ASSETS.md` Â§ 2 is the canonical target; `render/README.md` is the full spec â€” read it before touching any render code. The 2.5D DOOM renderer (visplanes, column renderer, 8-bit palette) is retired |
| Audio | `audio` | **SHIPPING, and deliberately five sounds.** Registered as `S_`; the port now comes from `I_AdapterFactory.getAudioPort()` rather than being a hard-coded null. `I_AudioPort` is six methods â€” play a `SoundId`, stop, clamp a volume, ask whether anything is audible â€” with `NullAudioPort` (silent, counts plays) and `GdxAudioPort` in `:gdxshared`, wired by `GdxAdapterFactory` and `AndroidAdapterFactory`. The weapon fires audibly from `DemoGameplayPort`, in two voices â€” the ordinary blaster and the super blaster a kill streak buys â€” beside the bots' carbine and the two-note chime that lands and then unwinds with the reward. **Every sound is generated, not shipped**: `BlasterSound`, `CarbineSound`, `SuperBlasterSound` and `PowerChimeSound` + `WavAudio` synthesise PCM at runtime, so there is no audio asset, no licence question and nothing in `NOTICE` to add â€” which is how the old "no audio row in `docs/ASSETS.md` Â§ 3" blocker was retired for the demo without pretending to have answered it for a real sound bank. **No mixer, no positional audio, no music, and none of them half-started** â€” `audio/README.md` keeps the 3D formulae as a reading list, explicitly not as a spec this code is failing to meet. Everything degrades: no device, headless JVM, unwritable cache all log once and keep playing |
| Network | `net` | **PARTIAL â€” the transport ships and peers are visible; match state is not replicated.** `NetSession` opens a real socket over `DesktopDatagramPort` and drives the primitives (`TicCmd`, `TicCmdBuffer`, `AckWindow`, `PeerConnection`, `RedundantSender`, `NetBytes`), plus `TicCmdEncoder` for the float-to-wire quantisation. `demo/RemotePlayers` replays each peer's commands through its own `PlayerController` on the shared `PhysicsWorld`, so two processes now draw each other's bodies â€” verified by loopback over two real sockets and by two live processes exchanging 77 KB with zero malformed packets and zero strangers. **What is NOT done: `Match` has never heard of a remote player**, so peers are visible but **not shootable**, and two divergences follow from that one gap, both measured rather than reasoned about: (1) a respawn is a teleport driven by `Match`, not by an input, so the peer replaying your commands never sees it â€” in an 11-second run the local player respawned while the other peer's copy was still walking 380 units away; (2) a late join costs a **constant** 17.07-unit offset (4 missing leading tics Ã— 4.267 units) because a body anchors on the oldest tic still in the ring â€” constant, not accumulating, since every later tic is applied exactly once. Desync detection (Â§ 10's periodic `stateHash`) is **deliberately not started**: it needs a second packet type and the shipped 20-byte header has no discriminator, and until match state is replicated the simulations are *known* to differ, so it would fire on every comparison and tell nobody anything |
| Resource | `resource` | `WadReader`, `LumpCache`, `MapLumpParser`, `LittleEndian`, `WadFilePort` all built (101 tests). **Not registered** â€” no `W_` subsystem yet. Its *role* is now an open question: `docs/ASSETS.md` moves all art to preprocessed glTF, so the WAD path has no art left to read. See `render/README.md` Â§ 11b. Do not delete it and do not build `ImageDecoder` until that is resolved |
| Memory | `memory` | Phase 1.1 â€” state machine, two backends (`JvmMemoryPort`, `ZoneMemoryPort`), factory |
| HAL | `hal` | Ports + `nulladapter` + `sqlite` + `desktop` (time, datagram) adapters, system info, user profile. Also `GameAction` / `InputBinding` / `ActionBindings` â€” the controls table. The engine owns which actions exist and deliberately ships **no default key codes**: a key code is a platform number and `:engine` may not import a toolkit that defines one, so each platform supplies its own table |
| *(not a subsystem)* | `demo` | `DemoScene`, `DemoModels`, `DemoEffects`, `DemoGameplayPort`, `RemotePlayers`, `BotFireVoices`, `BlockCarbine` â€” the playable match: room layout, solid geometry, kit scale, weapon pose, and seven bots with their routes, weapons, tracers, smoke and fire voices. Drives `Match` per tic and publishes each bot's placement to the renderer. `RemotePlayers` does the same for network peers. Every effect instance is **pre-allocated at build time and moved via `setWorldTransform`**, hidden with a degenerate transform rather than created and destroyed â€” `Scene` is immutable by design. Platform-free so `:tools` can render it headlessly and `:desktop` can run it live. Not registered with `SubsystemRegistry` |

| Module | Contains |
|---|---|
| `:engine` | Everything above. Pure Java 17, **no platform dependencies** â€” this is what CI builds and tests with no display and no Android SDK. Keep it that way |
| `:gdxshared` | The libGDX code that is **not** platform-specific: `FramebufferPresenter`, `RenderMode`/`RenderSettings`, the block welcome / settings / game-over screens, `UiState`/`UiStateMachine`, `InputAccumulator`, `AnalogStick`, `DebugSettings`/`DebugOverlay`/`FpsMeter`, `AccessibilitySettings`, `ScoreOverlay`, `GdxAudioPort`. **`AccessibilitySettings` is deliberately separate from `DebugSettings`** â€” the enemy outline is an accessibility feature that defaults ON, not a debug toggle that defaults off, and the two live in different menus for that reason. Depends on libGDX **core** and no backend, which is what lets both launchers use it and keeps it buildable with no display and no Android SDK. Adding a backend dependency here breaks one of the two platforms â€” see `gdxshared/README.md` |
| `:desktop` | libGDX LWJGL3 backend: `GdxWindowPort`, `GdxInputPort`, `DesktopBindings`, `GdxFrameLoopListener`, `GdxAdapterFactory`, `WindowIcon`, `NetArgs`, `DesktopLauncher` |
| `:android` | libGDX Android backend: `AndroidLauncher`, `AndroidWindowPort`, `AndroidAdapterFactory`, `AndroidUiFrameCallback`, the touch control scheme (`AndroidInputPort`, `TouchLayout`, `TouchOverlay`, `AndroidBindings`), `ApkModelSource`, Room-backed `RoomUserProfilePort`. **Playable** as of 2026-07-28. Only included in the build when an Android SDK is present (`ANDROID_HOME` / `ANDROID_SDK_ROOT` / `local.properties`) â€” otherwise `settings.gradle.kts` silently skips it, so a green `gradlew build` does **not** imply `:android` compiles |
| `:tools` | **Build-time only, never shipped.** glTF â†’ `ModelFormat` converter (`GltfConverter`), `ModelBuilder`, `MipGenerator`, `ProceduralRoom`, and the headless `DemoPreviewMain` / `RenderPreviewMain` harnesses. Depends on `:engine` one way; nothing depends on it. The root `verifyToolsIsolation` task fails the build if it ever reaches a runtime classpath |

Window and input adapters live in `:desktop` / `:android` rather than under
`hal/adapter/` precisely because they need libGDX and `:engine` must stay
platform-free. Both implement `I_WindowPort` and drive `I_FrameCallback` â€”
which is also the presentation path for the Phase 5 rasterizer. The engine
produces a finished framebuffer; the adapter uploads it.

**2465 tests passing** (1740 `:engine`, 300 `:gdxshared`, 177 `:android`,
164 `:desktop`, 78 `:tools`), Checkstyle clean — see `BUILD.md` for run
instructions and `README.md` § Test Coverage for the per-package breakdown.

The 2026-08 dead-code prune removed 7 unused public methods (see
`PRUNE_AUDIT.md`) and merged 1 redundant test; the net change is +4 tests
on `:engine` (some tests are written as `@Nested` so removing one method
sometimes loses a test file and gains it back through a different
counting route). The number of public methods on the engine dropped from
~190 to ~180; the number of source lines on the engine dropped by ~250.

The 2026-08 16-map library (Pass 1) added seven new test files on
`:engine` â€” `MatchModeTest`, `MatchMapSpecTest`, `MatchHardpointTest`,
`MatchDominationTest`, `MatchCtfTest`, `MapSpecTest`, `MapLibraryTest` â€”
and one new subpackage `gameplay/map/` carrying `MapSpec` / `MapLibrary`
/ `MapLoader` / `Maps` (factory) / the `MapMarkers` sealed interface and
its four implementations. Pass 5 (2026-08) added three more
`Maps.foundry()` / `Maps.mesa()` / `Maps.arcticHp()` factories and
the matching three HP spec entries in `MapLibraryTest` (+6 tests,
1712 → 1718 on `:engine`); the three new builders
(`FoundryMapBuilder` / `MesaMapBuilder` / `SubzeroMapBuilder`) sit in
`:tools` and the three new Gradle tasks (`buildFoundryMap` /
`buildMesaMap` / `buildSubzeroMap`) wire the optional Kenney-atlas
flag. Pass 6 (2026-08) added three more
`Maps.pipeline()` / `Maps.sandbar()` / `Maps.arcticDom()` (Frostline)
factories and the matching three Domination spec entries in
`MapLibraryTest` (+6 tests, 1718 → 1724 on `:engine`); the three new
builders (`PipelineMapBuilder` / `SandbarMapBuilder` /
`ArcticDomMapBuilder`) sit in `:tools` and the three new Gradle tasks
(`buildPipelineMap` / `buildSandbarMap` / `buildArcticDomMap`) wire
the optional Kenney-atlas flag. The first fully-implemented map is
`cornerstone` (Urban Warzone × TDM); 13 of the 16 are now FULL and the
three remaining design-only siblings are the CTF variants of the three
non-Urban settings. See `docs/maps/README.md`, `docs/pass1-report.md`,
`docs/pass5-report.md` and `docs/pass6-report.md`.
The `:engine` test count went from 1629 to 1724 (the +95 delta is the
sum of the `tests=` attribute of the new XML files, but several were
`@Nested` classes whose top-level numbers don't all add cleanly — count
the `<testsuite>` attributes, not the files or the `@Test`
annotations).

These are **distinct** tests, and every previous figure in this file was wrong
in one of two directions, so count them the same way or do not quote a number:
`:android:test` runs its suite against a debug and a release variant, so its raw
XML tally is 354 and double-counts everything; and a `@Nested` class writes its
own XML file, so counting files â€” or counting `@Test` annotations in source â€”
gives a third answer again. The figure above is the sum of the `tests=`
attribute of every `<testsuite>` Gradle actually wrote, `:android` counted once.

---

*Update this file when the project structure or conventions change.*
