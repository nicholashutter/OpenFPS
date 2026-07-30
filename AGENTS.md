# AGENTS.md — OpenFPS Engine Agent Instructions

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

Full list in `STYLE.md` § 13. Short version:

- All allocation → `I_MemoryPort` (via `MemoryPortFactory`)
- All inter-component comms → `I_EventBusPort` (via `EventBusFactory`)
- All parallel work → `WorkerPool` (via `ThreadPoolFactory`)
- All time reads → `I_TimePort` (never `System.nanoTime()`)
- All system info → `I_SystemInfoPort`
- All user profile reads/writes → `I_UserProfilePort` (never touch SQLite or Room directly)
- All fixed-point math → `FixedMath`
- All config (rate, maxTics) → `GameConfig` / `FrameRate`
- All subsystem classes → extend `Subsystem` (don't implement `Runnable`)
- All primitive constants → `Constants` (don't redeclare magic numbers)
- All logging → SLF4J (don't use `System.out`, `System.Logger`, etc.)
- All CORE-targeted events → `CoreSubsystem` (every `SubsystemId` needs an owner)

Anti-patterns in `STYLE.md` § 13.4 are instant review failures.

### Immutability-First
- All instance fields MUST be `final`
- All method parameters MUST be `final`
- Local variables SHOULD be `final` unless reassigned
- Prefer primitives over boxed types everywhere

### Control Flow
- **No ternary `?:`** — use `if`/`else`, an early return, or a `switch`
  (`STYLE.md` § 5.5). Switch *expressions* (`case X -> value`) are fine.
- **No nested lambdas** — one level is the hard limit; a lambda
  containing another `->` is a review failure (`STYLE.md` § 6.2).
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

The project is a **three-module Gradle build**: `:engine` (pure Java 17, no
platform dependencies, builds and tests headless anywhere), `:desktop`, and
`:android`. `:android` is only included when an Android SDK is present — see
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

# Run the engine headless (2s smoke run, then clean shutdown)
.\gradlew run
.\gradlew run --args="--fps=120 --headless"

# Run the windowed desktop client
.\gradlew :desktop:run

# Android: there is NO -Pandroid flag. :android is included automatically
# when ANDROID_HOME / ANDROID_SDK_ROOT / local.properties is present.
.\gradlew build
```

`JAVA_HOME` must point at a JDK 17+. `Unrecognized VM option 'UseZGC'` means
it is pointing at an old JDK — see `BUILD.md`.

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
        // OOM — never silent
    }
    // ... use handle, then memory.free(handle) when done
}
```

---

## What Not To Do

- Do NOT add external libraries without discussion
- Do NOT create `public static void main()` in non-core packages
- Do NOT write to `System.out` / `System.err` in production code — use **SLF4J** (`LoggerFactory.getLogger`)
- Do NOT use `System.Logger` or `java.util.logging` — they're banned in favor of SLF4J
- Do NOT import `java.util.List<Integer>` or any boxed collection in hot paths
- Do NOT add Android-specific code outside `hal.adapter.mobile`
- Do NOT skip updating `PLAN.md` section 7 when completing a roadmap item
- Do NOT `new byte[]` outside a memory port adapter. (The Phase 5 framebuffer
  is an unresolved conflict with this rule — it needs raw `int[]`/`float[]`
  access in a per-pixel loop, which `I_MemoryPort`'s handle indirection cannot
  give it. **Open question, not an exemption**: `render/README.md` § 11a states
  the options. Decide it before implementing `Framebuffer`; do not just do it.)
- Do NOT `System.nanoTime()` / `System.currentTimeMillis()` in engine code — use
  `I_TimePort` (`nanos()`/`millis()` monotonic, `epochMillis()` wall clock).
  Sanctioned exceptions: the time-port adapters, and shutdown timeouts that
  never feed simulation state.
- Do NOT `new Thread(...)` for event handling — use `WorkerPool`. The game loop
  is the exception: it is the producer, so it gets its own dedicated thread
  (`openfps-gameloop`). It cannot run on the pool it feeds (deadlock at
  `workerCount == 1`), and it cannot run on main either — **the main thread is
  reserved for the platform event pump**, because GLFW requires window calls
  and `glfwPollEvents()` there.
- Do NOT add magic numbers — use `Constants` or `FrameRate`
- Do NOT implement `Runnable` for a subsystem — extend `Subsystem`
- Do NOT instantiate `JvmMemoryPort` / `SharedEventBus` / `WorkerPool` / `SqliteUserProfilePort` directly — use the factories
- Do NOT add a new frame rate value — extend the `FrameRate` enum
- Do NOT touch SQLite or Room directly — go through `I_UserProfilePort`
- Do NOT skip writing tests for new code
- Do NOT use a ternary `?:` — use `if`/`else` or `switch` (`STYLE.md` § 5.5)
- Do NOT nest a lambda inside another lambda (`STYLE.md` § 6.2)
- Do NOT skip the documentation-to-code map in `STYLE.md` § 11 when adding a new service

---

## Subsystem Owners (Living)

> **Every package README opens with a `## Status` block. Read that first —
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
> The `State` field is a closed set — `SHIPPING` (built, wired, in use),
> `BUILT-UNWIRED` (tested but nothing calls it), `PARTIAL` (some of the named
> surface exists), `STUB` (port + null adapter only), `NOT STARTED`. Keep to
> those five so the blocks stay greppable.
>
> `BUILT-UNWIRED` is not a synonym for "done". `resource` is in that state and
> holds 101 tests that no running code exercises. `net` left it — a socket is
> opened now, and two live processes have been measured exchanging tics.

All paths below are under `engine/src/main/java/com/openfps/engine/` unless a
module is named. `:desktop`, `:tools` and `:android` carry the same block in
their module-root `README.md`.

| Subsystem | Package | Status |
|---|---|---|
| Core Loop | `core` | Phase 1.3 — event-driven, multi-threaded, configurable 30/60/120 Hz; pool also does caller-participating parallel fan-out (`submitParallel`) |
| Gameplay | `gameplay` | **SHIPPING**, registered as `P_`. `PlayerController` (first-person movement + gravity and a jump, StrictMath-deterministic), `Hitscan` / `Target` / `HitResult`, and the match layer: `Bot`, `BotPattern`, `Match`, `MatchState`, `MatchMode`. Bots walk closed-form routes — position at tic *n* is a pure function of *n*, so they cannot drift and a late-joining peer computes the same answer without replaying history — and shoot back through the same `Hitscan` the player's own weapon uses, so one bot genuinely blocks another's shot. **Three kills without dying** arm a super blaster for `SUPER_BLASTER_TICS` (240 tics, never milliseconds) at exactly twice `PLAYER_SHOT_DAMAGE`; a death takes both the streak and a live buff, `reset()` clears both, and a kill while it is live neither extends nor refreshes the window |
| Render | `render` | **Built and shipping** (409 tests), registered as `R_`. Multi-threaded software triangle rasterizer: `Framebuffer`, `Camera`, `TriangleClipper`, `Rasterizer`, `SpanRenderer`, `TextureSampler`, `MipChain`, `ModelFormat`, `Scene`, `SoftwareRenderPort`. Two passes per frame (world, then view-space viewmodel with a depth clear between). Measured p50 4.9 ms at 1280x720 on 8 workers. `docs/ASSETS.md` § 2 is the canonical target; `render/README.md` is the full spec — read it before touching any render code. The 2.5D DOOM renderer (visplanes, column renderer, 8-bit palette) is retired |
| Audio | `audio` | **SHIPPING, and deliberately five sounds.** Registered as `S_`; the port now comes from `I_AdapterFactory.getAudioPort()` rather than being a hard-coded null. `I_AudioPort` is six methods — play a `SoundId`, stop, clamp a volume, ask whether anything is audible — with `NullAudioPort` (silent, counts plays) and `GdxAudioPort` in `:gdxshared`, wired by `GdxAdapterFactory` and `AndroidAdapterFactory`. The weapon fires audibly from `DemoGameplayPort`, in two voices — the ordinary blaster and the super blaster a kill streak buys — beside the bots' carbine and the two-note chime that lands and then unwinds with the reward. **Every sound is generated, not shipped**: `BlasterSound`, `CarbineSound`, `SuperBlasterSound` and `PowerChimeSound` + `WavAudio` synthesise PCM at runtime, so there is no audio asset, no licence question and nothing in `NOTICE` to add — which is how the old "no audio row in `docs/ASSETS.md` § 3" blocker was retired for the demo without pretending to have answered it for a real sound bank. **No mixer, no positional audio, no music, and none of them half-started** — `audio/README.md` keeps the 3D formulae as a reading list, explicitly not as a spec this code is failing to meet. Everything degrades: no device, headless JVM, unwritable cache all log once and keep playing |
| Network | `net` | **PARTIAL — the transport ships, the simulation does not.** `NetSession` opens a real socket over `DesktopDatagramPort` and drives the primitives that were already built (`TicCmd`, `TicCmdBuffer`, `AckWindow`, `PeerConnection`, `RedundantSender`, `NetBytes`), plus `TicCmdEncoder` for the float-to-wire quantisation. Verified two ways: a loopback test over two real UDP sockets, and two live game processes, which exchanged 77 KB with zero malformed packets and zero strangers. **What is NOT done: remote players are not simulated into bodies.** That needs a `PlayerController` per peer driven from the received ring, plus a stall rule for a peer whose tics have not arrived. A session that exchanges packets perfectly and shows nobody looks exactly like a broken one, so do not read "multiplayer works" as more than the transport |
| Resource | `resource` | `WadReader`, `LumpCache`, `MapLumpParser`, `LittleEndian`, `WadFilePort` all built (101 tests). **Not registered** — no `W_` subsystem yet. Its *role* is now an open question: `docs/ASSETS.md` moves all art to preprocessed glTF, so the WAD path has no art left to read. See `render/README.md` § 11b. Do not delete it and do not build `ImageDecoder` until that is resolved |
| Memory | `memory` | Phase 1.1 — state machine, two backends (`JvmMemoryPort`, `ZoneMemoryPort`), factory |
| HAL | `hal` | Ports + `nulladapter` + `sqlite` + `desktop` (time, datagram) adapters, system info, user profile. Also `GameAction` / `InputBinding` / `ActionBindings` — the controls table. The engine owns which actions exist and deliberately ships **no default key codes**: a key code is a platform number and `:engine` may not import a toolkit that defines one, so each platform supplies its own table |
| *(not a subsystem)* | `demo` | `DemoScene`, `DemoModels`, `DemoGameplayPort` — the playable match: room layout, kit scale, weapon pose, and seven bots with their routes. Drives `Match` per tic and publishes each bot's placement to the renderer. Platform-free so `:tools` can render it headlessly and `:desktop` can run it live. Not registered with `SubsystemRegistry` |

| Module | Contains |
|---|---|
| `:engine` | Everything above. Pure Java 17, **no platform dependencies** — this is what CI builds and tests with no display and no Android SDK. Keep it that way |
| `:gdxshared` | The libGDX code that is **not** platform-specific: `FramebufferPresenter`, the block welcome screen, `UiState`/`UiStateMachine`, `InputAccumulator`. Depends on libGDX **core** and no backend, which is what lets both launchers use it and keeps it buildable with no display and no Android SDK. Adding a backend dependency here breaks one of the two platforms — see `gdxshared/README.md` |
| `:desktop` | libGDX LWJGL3 backend: `GdxWindowPort`, `GdxInputPort`, `DesktopBindings`, `GdxFrameLoopListener`, `GdxAdapterFactory`, `WindowIcon`, `NetArgs`, `DesktopLauncher` |
| `:android` | libGDX Android backend: `AndroidLauncher`, `AndroidWindowPort`, `AndroidAdapterFactory`, `AndroidUiFrameCallback`, the touch control scheme (`AndroidInputPort`, `TouchLayout`, `TouchOverlay`, `AndroidBindings`), `ApkModelSource`, Room-backed `RoomUserProfilePort`. **Playable** as of 2026-07-28. Only included in the build when an Android SDK is present (`ANDROID_HOME` / `ANDROID_SDK_ROOT` / `local.properties`) — otherwise `settings.gradle.kts` silently skips it, so a green `gradlew build` does **not** imply `:android` compiles |
| `:tools` | **Build-time only, never shipped.** glTF → `ModelFormat` converter (`GltfConverter`), `ModelBuilder`, `MipGenerator`, `ProceduralRoom`, and the headless `DemoPreviewMain` / `RenderPreviewMain` harnesses. Depends on `:engine` one way; nothing depends on it. The root `verifyToolsIsolation` task fails the build if it ever reaches a runtime classpath |

Window and input adapters live in `:desktop` / `:android` rather than under
`hal/adapter/` precisely because they need libGDX and `:engine` must stay
platform-free. Both implement `I_WindowPort` and drive `I_FrameCallback` —
which is also the presentation path for the Phase 5 rasterizer. The engine
produces a finished framebuffer; the adapter uploads it.

**1578 tests passing** (1177 `:engine`, 86 `:gdxshared`, 119 `:desktop`, 73 `:tools`, 123 `:android` — distinct tests; `:android:test` runs its suite twice, once per variant), Checkstyle clean — see
`BUILD.md` for run instructions and `PLAN.md` § 8 for the per-suite breakdown.

---

*Update this file when the project structure or conventions change.*
