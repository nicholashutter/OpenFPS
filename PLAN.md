# OpenFPS — Project Plan

> **Status**: Pre-alpha — Architecture definition phase
> **Engine Version**: 0.1.0-SNAPSHOT
> **Target JVM**: 21 LTS
> **Platforms**: Windows, Linux, Android, JVM-compatible targets

---

## 1. Vision

OpenFPS is a ground-up FPS game engine written in Java targeting the JVM runtime. It prioritizes memory efficiency, cross-platform portability, and a clean adapter/port (hexagonal) architecture so that platform-specific code lives at the edges, never in the core. Networking is peer-to-peer first; no authoritative server required for small-scale matches.

The engine draws direct inspiration from the original Doom (id Software, 1993) subsystem layout — not as cargo culting, but because that architecture genuinely separates concerns well for a real-time game loop. We borrow the letter-prefix convention (D_, P_, R_, S_, G_, W_, Z_, I_) as a loving homage and a clear naming signal.

---

## 2. Architecture Overview

```
+-------------------------------------------------------------------+
|  MAIN GAME LOOP (com.openfps.engine.core)                         |
|  Maintains game state, tics, and timing                          |
+-------------------------------------------------------------------+
|                                                                   |
|  v  v  v  v                                                     |
+--------+  +-----------+  +--------+  +--------+  +------------+   |
|  P_    |  |    R_     |  |   S_   |  |   G_   |  |    W_      |  |
| Game-  |  |  Render   |  | Audio  |  |  Net   |  | Resource  |  |
| play   |  | (stubbed) |  |(stubbed|  |  P2P   |  |  / WAD    |  |
+--------+  +-----------+  +--------+  +--------+  +------------+  |
|  Core  |  |           |  |         |  |         |  |           |  |
|  Game  |<-|---------->|<-|-------->|<-|-------->|<-|----------|  |
| Loop   |  |           |  |         |  |         |  |           |  |
+--------+  +-----------+  +--------+  +--------+  +------------+  |
                          |           |           |               |
                   +------+           +-----------+               |
                   |                                           |
+------------------|-------------------------------------------|---+
|  MEMORY / ZONE ALLOCATOR  (com.openfps.engine.memory)         |   |
|  W_ WAD Manager           (com.openfps.engine.resource)      |   |
+-----------------------------------------------------------------+---+
|  v                                                             |
+-------------------------------------------------------------------+
|  HARDWARE ABSTRACTION LAYER  (com.openfps.engine.hal)          |
|  Ports = interfaces, Adapters = platform implementations       |
|                                                                   |
|  I_InitGraphics()  I_GetTimeMs()  I_SubmitAudio()  I_NetSend()  |
|  I_NetRecv()       I_ReadInput()  I_AllocBuffer()               |
+-------------------------------------------------------------------+
|  v                                                             |
+-------------------------------------------------------------------+
|  PLATFORM ADAPTERS                                               |
|  desktop/   — LWJGL3 + OpenAL + NIO sockets (Windows/Linux)     |
|  mobile/    — Android Canvas + AudioTrack + DatagramSocket       |
|  null/      — headless stubs for unit testing                    |
+-------------------------------------------------------------------+
```

---

## 3. Subsystem Specification

Each subsystem lives in its own package under `com.openfps.engine.<name>`.
Every subsystem has:
- **port/** — interfaces (the "port" side of hexagonal arch)
- **adapter/** — concrete implementations (the "adapter" side)

### 3.1 Core — `com.openfps.engine.core`
**D_ Game Loop**

- Maintains a fixed 35 Hz tic rate (Doom standard, adjustable)
- Manages `GameState`: current map, player list, entity list, tic number
- Calls each subsystem in order: `P_Ticker`, `G_Ticker`, `W_Ticker`, `R_Ticker`, `S_Ticker`
- Provides `TicCounter`, `TimeSource`, `GameConfig`
- Implements `Runnable`; can be embedded or run standalone

### 3.2 Gameplay — `com.openfps.engine.gameplay`
**P_ Player & World Logic**

- `PlayerState`: position (fixed-point), velocity, angle, pitch, health, inventory
- `Entity`: abstract base for all game objects (players, projectiles, pickups, doors)
- `PhysicsWorld`: collision detection, gravity, sliding along walls (BSP-assisted)
- `MapSubsector`: sector adjacency, portal handling
- Ports: `PlayerController`, `EntityFactory`, `PhysicsPort`

### 3.3 Render — `com.openfps.engine.render`
**R_ Rendering** (stubbed initially)

- `BspTraverser`: walks the BSP tree front-to-back
- `WallClipper`: portal-based wall rendering
- `VisplaneBuilder`: screen-space horizontal band management
- Ports: `RendererPort`, `TexturePort`, `FrameBufferPort`
- Adapters initially null; wire up LWJGL3 later

### 3.4 Audio — `com.openfps.engine.audio`
**S_ Sound**

- `SoundEngine`: manages SFX and music playback
- `SoundEmitter`: 3D-positioned sound sources
- Ports: `AudioPort`, `MusicPort`
- Adapters initially null; wire up OpenAL / AudioTrack later

### 3.5 Network — `com.openfps.engine.net`
**G_ Peer-to-Peer Networking**

- `PeerConnection`: UDP socket per connected peer
- `TicCmdBuffer`: ring buffer of player inputs per tic
- `NetState`: authoritative list of connected peers, latency tracking
- `ProtocolHandshake`: NAT punch-through signaling (initial: LAN broadcast discovery)
- `SnapshotDelta`: diff-based state serialization between tics
- Ports: `NetworkPort`, `DiscoveryPort`
- Target: Java NIO `DatagramChannel` for non-blocking I/O

### 3.6 Resource — `com.openfps.engine.resource`
**W_ WAD File Management**

- `WadReader`: reads `.wad` lumps (textures, maps, flats, sprites)
- `LumpCache`: demand-loaded, reference-counted lump cache
- `MapLumpParser`: parses DOOM-format map lumps (GL_VERT, THINGS, LINEDEFS, etc.)
- Ports: `ResourcePort`, `LumpPort`

### 3.7 Memory — `com.openfps.engine.memory`
**Z_ Zone Allocator**

- Custom slab allocator for frequently allocated game objects
- Configurable zone heap size; bypasses JVM GC for hot-path allocations
- `Z_Alloc`, `Z_Free`, `Z_Realloc` equivalents
- Provides `Z_Tag` for bulk free on level change
- Ports: `MemoryPort`

### 3.8 HAL — `com.openfps.engine.hal`
**I_ Hardware Abstraction Layer**

- `TimeSource`: monotonic millisecond counter
- `InputSource`: keyboard + mouse + gamepad abstraction
- `GraphicsPort`: framebuffer surface
- `AudioHAL`: raw audio buffer submission
- `NetworkHAL`: raw send/receive
- `FileSource`: file I/O abstraction for WAD loading
- `ThreadPort`: managed thread creation for audio/render/net threads

---

## 4. Data Layout Conventions

- **Fixed-point arithmetic**: `FIXED` = `int` storing value × 65536 (1.0 = 0x10000)
- **Angles**: stored as `int` in degrees × 65536 (360° = 0x10000 * 360 ≈ 23592960)
- **Coordinates**: `x`, `y`, `z` each `int` fixed-point
- **Entity ID**: `int` — unique per-level, rolled over on map load
- **Tic number**: `int` — monotonically increasing from game start, no cap
- **Player slot**: `int` — max 4 players initially, 8 later

---

## 5. Module Dependency Graph

```
core ──────────────┬──► gameplay
                   ├──► render
                   ├──► audio
                   ├──► net
                   ├──► resource ──► memory
                   └──► hal

hal ───────────────┼──► adapter/desktop  (LWJGL3)
                   ├──► adapter/mobile   (Android)
                   └──► adapter/null     (testing)
```

No cyclic dependencies. Core depends on ports only. Adapters depend on nothing above them.

---

## 6. Build Configuration

| Target | Toolchain | Notes |
|---|---|---|
| Desktop (Win/Linux) | Java 21 + LWJGL 3.3.4 | OpenGL 2.1 / OpenAL |
| Android | Java 21 source, Android SDK 34 | Uses `-Pandroid` profile |
| Headless / Test | Java 21 only | null HAL adapter |

Gradle is the single build tool. See `BUILD.md` for commands.

---

## 7. Project Roadmap

### Phase 0 — Scaffolding (this commit)
- [x] Git repo, project plan, style guide
- [x] Gradle build (desktop + android profile)
- [x] Package skeleton with port/adapter stubs
- [x] Checkstyle config

### Phase 1 — Core Loop + HAL Ports (TBD)
- [x] `EngineMain` entry point
- [x] `GameLoop` with 35 Hz ticker
- [x] HAL port interfaces (Time, Input, File)
- [x] Null adapter for testing
- [ ] Desktop adapter (basic LWJGL3 window + time)

### Phase 2 — Memory + Resource (TBD)
- [x] `ZoneAllocator`
- [x] `WadReader`
- [ ] WAD format coverage: headers, lumps, caching

### Phase 3 — Networking (TBD)
- [ ] `PeerConnection` UDP
- [ ] `TicCmdBuffer` ring buffer
- [ ] `DiscoveryPort` LAN broadcast
- [ ] Snapshot delta serialization

### Phase 4 — Gameplay (TBD)
- [ ] `PlayerState`, `Entity`
- [ ] `PhysicsWorld` (collision + sliding)
- [ ] Map loading from WAD lumps

### Phase 5 — Render (TBD)
- [ ] BSP traversal
- [ ] Wall clipper
- [ ] Visplane builder

### Phase 6 — Audio (TBD)
- [ ] OpenAL adapter
- [ ] Sound emitter system

---

## 8. Contribution Guidelines

1. All PRs must pass `checkstyle` and `gradle build`
2. New subsystems require port interfaces before any adapter implementation
3. No subsystem may import from another subsystem's adapter package
4. All public API must be documented with Javadoc
5. Breaking changes to public API require a major version bump

---

## 9. Document-to-Code Mapping

This plan is the source of truth. When code is written:
- Update the relevant subsystem section with implementation notes
- Mark `[x]` items in the roadmap as they complete
- Keep section numbers stable; append `-impl` notes below the specification

When in doubt, the module owner (document owner) resolves ambiguity.
