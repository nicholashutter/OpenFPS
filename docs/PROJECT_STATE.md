# Project State Snapshot — OpenFPS

> Compact state of the OpenFPS Java 17 game engine as of 2026-08-10. Read this
> first if you're picking up after a long pause; the full docs in
> `README.md` / `AGENTS.md` / `PLAN.md` / `STYLE.md` are the source of truth
> for conventions and architecture.

## TL;DR

- **5-module Gradle build** (`:engine`, `:gdxshared`, `:desktop`, `:tools`, `:android`), Java 17, libGDX LWJGL3 + Android backends, MIT license.
- **2,491 tests passing** (1,740 `:engine`, 110 `:tools`, 300 `:gdxshared`, 164 `:desktop`, 177 `:android`), build green, Checkstyle clean (`maxWarnings = 0`).
- **16-map multiplayer library complete**: 4 settings × 4 modes (TDM / Hardpoint / Domination / CTF) at BO6/BO7 sizing, three-lane COD style, Kenney-textured.
- **Config-driven map generator** (`:tools:mapgen`): a new map is a JSON file + a Gradle task, not 400 lines of Java.
- **No blocker gaps**. Open items are follow-ups, not road-blockers.

## 16-Map Library

All 16 maps fully implemented. The grid:

|             | TDM          | Hardpoint    | Domination   | CTF           |
|-------------|--------------|--------------|--------------|---------------|
| **Urban Warzone**     | `cornerstone` | `overpass`   | `tripoint`   | `extraction`  |
| **Industrial Complex**| `refinery`    | `foundry`    | `pipeline`   | `storage`     |
| **Desert Ravine**     | `crossroads`  | `mesa`       | `sandbar`    | `stronghold`  |
| **Arctic Station**    | `arctic-station` | `arctic-hp` | `arctic-dom` | `coldfront`   |

Each map has:
- A design spec at `docs/maps/<setting>/0N-<id>.md` (FULL status, 6-9 KB)
- A `Maps.<id>()` factory in `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java`
- A `MapSpec` registered in `MapLibrary.registerDefaults()`
- A level `.ofm` at `engine/src/main/resources/maps/<id>/level.ofm`
- A builder at `tools/src/main/java/com/openfps/tools/<Id>MapBuilder.java` (hand-written Java)
- A smoke test: `.\gradlew.bat :engine:run --args="--headless --map=<id> --fps=60"` runs 120 tics, exit=0

**Note**: `coldfront` is the Arctic CTF map; it was renamed from "arctic-ctf" in Pass 5 to match the design spec display name.

## Mode Logic

All four modes fully implemented and tested (Passes 2-4 did the heavy lifting):

| Mode | File | Tests | Behavior |
|---|---|---|---|
| TDM | Original | 30+ | Respawn + score per kill |
| Hardpoint | `Match.updateHardpoint` | 13 (`MatchHardpointTest`) | Zone rotation, capture, per-tic score |
| Domination | `Match.updateDomination` | 13 (`MatchDominationTest`) | 3 flags, capture, per-tic score per held flag |
| CTF | `Match.updateCtf` | 16 (`MatchCtfTest`) | Pickup, drop-on-death, return, capture, 5-capture win |

## Map Generator

Config-driven pipeline. New primitive types are added via the `PrimitiveFactory` registry — no generator changes.

| Path | Purpose |
|---|---|
| `tools/src/main/java/com/openfps/tools/mapgen/` | 9 files: `MapGenConfig`, `JsonConfigParser`, `Primitive`, `PrimitiveFactory`, `Box`, `Sign`, `KenneySwatch`, `MapGenerator`, `MapGenMain` |
| `tools/config/maps/sample-cornerstone.json` | POC: produces a 300-tri, 4-submesh `.ofm` |
| `tools/build.gradle.kts` | The `:tools:buildMapFromConfig` task |

Built-in primitives: `box` (closed axis-aligned solid) and `sign` (flat plane). Both have Javadoc that explains the geometry, the UV computation, and the orientation rules.

**Full HTML doc**: `docs/mapgen.html` (23 KB, single self-contained file, embedded CSS, no external assets).

## How to Run

```powershell
# Headless smoke test (any map):
.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"

# Windowed (after MapScene + DesktopLauncher wiring, both done in Pass 2):
.\run-desktop.ps1 -StartInGame -DebugOverlay
.\gradlew.bat :desktop:run --args="--map=cornerstone"

# Build the new map from a config file:
.\gradlew.bat :tools:buildMapFromConfig -Pconfig=tools\config\maps\sample-cornerstone.json -PmapId=sample-cornerstone

# Full test suite:
.\gradlew.bat test

# Checkstyle only:
.\gradlew.bat checkstyleMain
```

## Architecture (one-liner)

`Engine.init()` wires memory, HAL, bus, pool, subsystems. `D_GameLoop` runs at 30/60/120 Hz. Events flow through `SharedEventBus` to `WorkerPool` (cores-1 threads). Subsystems (`P_` Gameplay, `R_` Render, `S_` Audio, `G_` Net, `W_` Resource, `Z_` Memory, `I_` HAL) are state machines. The engine is platform-free; adapters in `:desktop` / `:android` wire it up.

The software rasterizer (`R_`) is multi-threaded via tile ownership. Measured at 4.9 ms p50 at 1280x720 on 8 workers (16.6 ms frame budget). Bit-identical output at every worker count. `RenderMode` decouples render resolution from surface size.

## File Counts (rough)

| Path | Main files | Test files |
|---|---|---|
| `engine/src/main/java/com/openfps/engine/` | ~165 (after the map library expansion) | — |
| `engine/src/test/java/com/openfps/engine/` | — | ~95 |
| `tools/src/main/java/com/openfps/tools/` | ~25 (16 MapBuilders + mapgen + supporting) | — |
| `tools/src/test/java/com/openfps/tools/` | — | ~7 |
| `gdxshared/`, `desktop/`, `android/` | ~55 | ~30 |

## Open Items (not blockers)

1. **Refactor the 16 hand-written `*MapBuilder.java` classes to JSON configs.** The generator exists; the migration is mechanical. A future pass.
2. **`SubzeroMapBuilder.java` is orphaned** — exists in `tools/` but isn't registered in `MapLibrary` or referenced in `Maps.java`. Probably an off-script agent artifact. Clean up or register.
3. **The deferred Pass 7 (3 CTF maps) is done** — `storage`, `stronghold`, `coldfront` all shipped, all Kenney-textured, all smoke-test clean. The `coldfront` rename is the one naming wrinkle to know about.
4. **`MapScene` for the windowed path** is built and wired (Pass 2). `:desktop:run --map=<id>` works.
5. **Tracer / smoke improvements** landed in the mapgen pass: bigger puffs, longer life, three colour variants round-robin per spawn. The tracer itself was already correctly designed; the agent left it as-is.
6. **`Match.updateCtf` uses `CTF_CAPTURE_LIMIT = 5` and `CTF_TIME_LIMIT_TICS = 60 * 60 * 10`** (5 captures or 10 minutes). Defaults in `Match.java`. A future pass can tune them per map.
7. **`DemoEffectsTest$Incoming.thePoolFitsTheWholeRoom` was rewritten** to assert the general relationship `MAX_BOT_PUFFS = ⌈life/cooldown⌉ × botCount + 1` rather than the special case. The new math is correct; the rewrite was needed.

## Git State

Last commit on the working tree:
```
20c6209 Prune dead code per PRUNE_AUDIT (2026-08-05)
```

Everything since then (Passes 1-6, mapgen pass, smoke test improvements) is **uncommitted and unstaged**. The pass reports (1-7 + mapgen) document the work; commit when ready.

## Stale or Out-of-Date Files to Know About

- `tools/build.gradle.kts` — has dirty diff (not committed, whitespace + new build tasks).
- `engine/src/main/resources/maps/*/level.ofm` — 17 files (16 maps + sample-cornerstone), all staged for commit via `git add -f` to override the `*.ofm` gitignore rule.
- The 16 `*MapBuilder.java` files are functionally replaced by `mapgen/` for new maps; they're kept for now as a reference but the JSON path is the recommended one for any future map.
- `docs/pass1-report.md` through `docs/pass7-report.md` + `docs/mapgen-and-effects-report.md` are the work logs; read them for the per-pass history.

## Where to Start If You Have an Hour

1. Read `AGENTS.md` and `STYLE.md` (the project rules; the codebase enforces them).
2. Skim `README.md` § Architecture and § Renderer.
3. Run the headless smoke test (`.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"`) to confirm the toolchain works.
4. Open `docs/mapgen.html` in a browser to see the generator architecture.
5. Pick a `pass*-report.md` (Pass 1-7) to read for context on what shipped in each phase.
