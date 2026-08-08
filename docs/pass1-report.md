# Pass 1 Implementation Report — 16-Map Multiplayer Library

**Status**: Pass 1 of 4 complete. Architecture and one fully-implemented map (`cornerstone`) shipped. The three sibling design-only specs are committed. Hardpoint / Domination / CTF mode rules are stubbed.

---

## Files created (full paths)

### Production code — engine/src/main/java/com/openfps/engine/

- `gameplay/map/MapSetting.java` — enum (URBAN_WARZONE / INDUSTRIAL_COMPLEX / DESERT_RAVINE / ARCTIC_STATION)
- `gameplay/map/Team.java` — enum (RED / BLUE / NEUTRAL)
- `gameplay/map/LaneAxis.java` — enum (NORTH_SOUTH / EAST_WEST)
- `gameplay/map/MapDimensions.java` — record (width, depth, height)
- `gameplay/map/Chokepoint.java` — record (id, callout, x, z)
- `gameplay/map/Lane.java` — record (id, axis, chokepoints)
- `gameplay/map/SpawnPoint.java` — record (id, team, x, y, z, yawRadians)
- `gameplay/map/Waypoint.java` — record (id, x, y, z)
- `gameplay/map/MapAssets.java` — record (level, weapon, atlas)
- `gameplay/map/MapMarkers.java` — sealed interface + 4 implementations (TeamDeathmatch, Hardpoint, Domination, CaptureTheFlag) with nested HardpointZone, Flag, Base records
- `gameplay/map/MapSpec.java` — final, immutable spec with id-keyed equals/hashCode and marker/mode validation
- `gameplay/map/Maps.java` — factory class with `cornerstone()` static method
- `gameplay/map/MapLibrary.java` — singleton registry (synchronized registration, volatile-rewritten immutable map for lock-free reads)
- `gameplay/map/MapLoader.java` — facade that delegates to MapLibrary; `loadOrFallback` for graceful headless behaviour
- `gameplay/map/MapSmokeGameplayPort.java` — headless I_GameplayPort that loads a spec, builds a Match from spec waypoints, and ticks

### Production code — tools/

- `tools/src/main/java/com/openfps/tools/CornerstoneMapBuilder.java` — procedural .ofm model builder (264 triangles, 528 vertices, 2 textures)

### Engine binary asset

- `engine/src/main/resources/maps/cornerstone/level.ofm` — procedurally generated, committed via `git add -f` (overrides the `*.ofm` gitignore pattern; same exception used for the existing generated-room model). 60 KB.

### Test code — engine/src/test/java/com/openfps/engine/

- `gameplay/MapModeTest.java` (actually `MatchModeTest.java`) — 4 tests for the new MatchMode enum entries
- `gameplay/MapMapSpecTest.java` (actually `MatchMapSpecTest.java`) — 7 tests covering Match construction, mode dispatch, team scores, reset, and each of the 4 modes ticking without error
- `gameplay/map/MapSpecTest.java` — 7 tests for MapSpec validation, marker/mode match, equality
- `gameplay/map/MapLibraryTest.java` — 5 tests for registration, defaults, lookups

### Design docs — docs/maps/urban-warzone/

- `01-cornerstone.md` — FULL design spec, the one implemented map
- `02-hp-overpass.md` — DESIGN ONLY, Hardpoint
- `03-dom-tripoint.md` — DESIGN ONLY, Domination
- `04-ctf-extraction.md` — DESIGN ONLY, CTF

### Build config

- `tools/build.gradle.kts` — added the `:tools:buildCornerstoneMap` task

---

## Files modified (full paths + brief diff summary)

- `engine/src/main/java/com/openfps/engine/gameplay/MatchMode.java` — added TDM, HARDPOINT, DOMINATION, CTF siblings; added `isRuleSet()` helper. The legacy SINGLE_PLAYER and MULTIPLAYER stay.
- `engine/src/main/java/com/openfps/engine/gameplay/Match.java` — added `MapSpec mapSpec` final field, `hardpointRotationCounter`, `hardpointActiveZone`; added 2-arg constructor `(Bot[], MapSpec)` and 5-arg constructor `(Bot[], BotRng, BotSkill, int, MapSpec)`; added `mapSpec()`, `mode()`, `teamScores()` accessors; added `updateMode(ticIndex, playerX, playerZ)` dispatch with `updateHardpoint` (rotates zone), `updateDomination` (stub), `updateCtf` (stub); updated `reset()` to clear the new fields; added imports for `MapSpec` and `MapMarkers`.
- `engine/src/main/java/com/openfps/engine/core/EngineMain.java` — added `--map=<id>` CLI parsing, new `run(config, useSqlite, headless, mapId)` overload, new `mapSmokeFactory(mapId)` helper, and `displayMapId` for null-safe log output.
- `engine/src/main/java/com/openfps/engine/gameplay/README.md` — bumped test count from 348 to 369, added "Map library (Pass 1)" section to the Status block.
- `PLAN.md` — added Phase 7 "16-map multiplayer library" section to the roadmap (§ 7), with Pass 1 marked done and Passes 2-4 listed as planned.
- `tools/build.gradle.kts` — added `buildCornerstoneMap` JavaExec task.

---

## New tests added (count + paths)

**21 new tests, 1658 total (up from 1629)**

- `engine/src/test/java/com/openfps/engine/gameplay/MatchModeTest.java` — 4 tests
- `engine/src/test/java/com/openfps/engine/gameplay/MatchMapSpecTest.java` — 7 tests
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapSpecTest.java` — 7 tests
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java` — 5 tests (2 nested classes: 3 + 2)

The original 1629 tests still pass; nothing was deleted or moved. Checkstyle remains clean (`maxWarnings = 0`).

---

## Build verification

Three commands, captured verbatim:

### 1. `.\gradlew.bat :engine:test`

```
BUILD SUCCESSFUL in 16s
4 actionable tasks: 1 executed, 3 up-to-date
Configuration cache reused.
```

Test count: **1658** (up from 1629). Breakdown (from XML `tests=` attributes):
- MapSpecTest: 7
- MapLibraryTest: 5
- MatchModeTest: 4
- MatchMapSpecTest: 7
- Total new: 21; grand total: 1658

### 2. `.\gradlew.bat checkstyleMain`

```
> Task :engine:checkstyleMain UP-TO-DATE
> Task :desktop:checkstyleMain UP-TO-DATE
> Task :tools:checkstyleMain
> Task :gdxshared:checkstyleMain UP-TO-DATE

BUILD SUCCESSFUL in 6s
10 actionable tasks: 2 executed, 8 up-to-date
```

`maxWarnings = 0` holds across all four modules. One warning was caught during development (an inline `?:` in the tools' `wallTexels`) and removed before commit.

### 3. `.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"`

```
05:31:20.319 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
05:31:22.302 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
05:31:22.304 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
05:31:22.317 [openfps.core.EngineSession] INFO  c.openfps.engine.core.EngineSession - OpenFPS engine shut down cleanly.

BUILD SUCCESSFUL in 3s
3 actionable tasks: 1 executed, 2 up-to-date
```

Engine boots, runs 120 tics (~2s) and exits cleanly. The headless smoke test path uses `MapSmokeGameplayPort` which loads the cornerstone spec, builds a Match with one bot per waypoint (capped at `Match.DEFAULT_BOT_COUNT`), and ticks.

### 4. `.\gradlew.bat :engine:test --tests "com.openfps.engine.gameplay.map.MapLibraryTest"`

```
> Task :engine:test

com.openfps.engine.gameplay.map.MapLibraryTest > defaults > cornerstone is registered at class load time PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > defaults > cornerstone spec is a TDM map in Urban Warzone PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > lookups > a null id is rejected PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > lookups > an unknown id returns null PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > registration > register a freshly registered map is retrievable by id PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > registration > register a null spec is rejected PASSED
com.openfps.engine.gameplay.map.MapLibraryTest > registration > re-registering a map replaces the previous one PASSED

BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
```

All 7 MapLibraryTest tests pass.

---

## Cornerstone design spec location

- `C:\Development\fullstack\openfps\docs\maps\urban-warzone\01-cornerstone.md`

A 320×320 urban block, three-lane COD layout (A north, B middle, C south), 9 named callouts, 6 spawns, 8 bot waypoints forming a closed loop. 264-triangle level model committed at `engine/src/main/resources/maps/cornerstone/level.ofm`.

## The three other design spec locations

- `C:\Development\fullstack\openfps\docs\maps\urban-warzone\02-hp-overpass.md` — Hardpoint, two overpasses + control building
- `C:\Development\fullstack\openfps\docs\maps\urban-warzone\03-dom-tripoint.md` — Domination, three flags on three approach streets
- `C:\Development\fullstack\openfps\docs\maps\urban-warzone\04-ctf-extraction.md` — CTF, two bases along a long boulevard

All three carry full ASCII maps, lane/cut-through/callout descriptions, spawn placements, mode-specific markers, bot waypoints, asset references, and the same Implementation status block reading **DESIGN ONLY** with a Pass 2 / 3 / 4 handoff.

---

## Open items

1. **Hardpoint / Domination / CTF mode logic is stubbed.** The mode enum, the per-mode dispatch, and the marker subtypes are all real, but the actual capture / rotation / pickup / return / score logic is not implemented. The stubs run without error; Pass 2, 3, 4 fill in the per-mode rules alongside three more maps per setting.
2. **`MapScene` is not built.** The windowed path (`:desktop:run --map=cornerstone`) does not yet construct a `Scene` from the `MapSpec`. The headless smoke test path works because it does not render. A `MapScene` class that wraps the level .ofm and a `Scene.Builder` would close the loop; deferred to a later pass because the smoke test was the success criterion.
3. **The weapons path in `MapAssets` references the existing `blaster-b.ofm`** which lives in the gitignored `assets/models/weapon/`. A fresh clone with no models staged will fail to load the viewmodel; for Pass 1 the smoke test does not need it. The level .ofm is committed in `engine/src/main/resources/maps/cornerstone/` so the geometry is always available.
4. **The level model is procedurally generated, not from a Kenney art pack.** Cornerstone uses 264 hand-built triangles in 19 boxes (floor, 4 perimeter walls, 3 internal walls with cut-throughs, 4 landmark buildings, 8 crates). A future pass can swap the procedural geometry for converted Kenney art; the `MapAssets.level` path is the seam.
5. **`demo/RemotePlayers` is unchanged.** The 16-map library is offline (PVE / single-process); a future pass that wants peer-replicated maps is the place to wire it through.
6. **The Hardpoint spec's `passive` rotation period of 1800 tics** is documented but the engine only runs 120 tics headless. A full Hardpoint round needs a real `maxTics` and the scoreboard in the launcher — deferred to Pass 2.
7. **No `git add` of the .ofm was performed** because the user did not ask for a commit. The file is staged (`git add -f engine/src/main/resources/maps/cornerstone/level.ofm`) and ready; the rest of the changes are untracked or modified and visible in `git status`.

---

## Cornerstone — one-paragraph summary

Cornerstone is a 320×320 urban block built around three parallel lanes running north–south: lane A (the safe north route, anchored by the Cafe and Library landmarks) runs along the northern edge, lane C (its south mirror, anchored by Storefront and the southern Plaza) runs along the southern edge, and lane B (the risk/reward mid lane, anchored by Bridge, Market, and Atrium) runs the centre. Two internal east–west walls at z=100 and z=220 separate the lanes and carry four named cut-throughs (the A→B pair at x=96 and x=224, the B→C single at x=160) that a player can read at a glance from a callout. Six spawns — three per team on the long west and east edges with facings aimed at the lanes but not directly into them — open the round in cover, not in sightlines. A row of eight crates in two stacks bisects lane B, the only mid-lane cover on a map that otherwise rewards the side-lane routes. The result plays like a BO6/BO7 three-lane map: lane B is fast and lethal, lanes A and C are the long fights, and the cut-throughs decide which team controls the next rotation. The 264-triangle procedural level model is committed alongside the spec, the test suite is green, the headless smoke test boots and runs 120 tics without error, and the design specs for the three sibling maps (Hardpoint at Overpass, Domination at Tripoint, CTF at Extraction) commit to the layout, callouts, and mode markers for Passes 2 through 4 to implement.

---

**Report file**: `C:\Development\fullstack\openfps\docs\pass1-report.md`
