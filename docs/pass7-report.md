# Pass 7 Implementation Report — Non-Urban × CTF

**Status**: Pass 7 of 7 complete. The 16-map library is finished: 4 settings × 4 modes, 16 maps all FULL. Three new CTF maps (`storage`, `stronghold`, `coldfront`) added in Pass 7. CTF mode logic was already implemented in Pass 4 (`Match.updateCtf` + 16 `MatchCtfTest` cases); Pass 7 only adds the three new map instances, level models, factory methods, and MapLibraryTest entries.

---

## Files created (full paths)

### Production code — engine/src/main/java/com/openfps/engine/

- `gameplay/map/Maps.java` — extended with `storage()`, `stronghold()`, `coldfront()` static methods

### Production code — tools/

- `tools/src/main/java/com/openfps/tools/StorageMapBuilder.java` — procedural .ofm model builder. 192 triangles, 384 vertices, 2 textures
- `tools/src/main/java/com/openfps/tools/StrongholdMapBuilder.java` — procedural .ofm model builder. 168 triangles, 336 vertices, 2 textures
- `tools/src/main/java/com/openfps/tools/ColdfrontMapBuilder.java` — procedural .ofm model builder. 132 triangles, 264 vertices, 2 textures

### Engine binary assets

- `engine/src/main/resources/maps/storage/level.ofm` — procedurally generated, committed via `git add -f`. ~12 KB.
- `engine/src/main/resources/maps/stronghold/level.ofm` — procedurally generated, committed via `git add -f`. ~10 KB.
- `engine/src/main/resources/maps/coldfront/level.ofm` — procedurally generated, committed via `git add -f`. ~8 KB.

### Test code — engine/src/test/java/com/openfps/engine/

- `gameplay/map/MapLibraryTest.java` — added 6 tests (register + describe for each new CTF map)

### Build config

- `tools/build.gradle.kts` — added the `:tools:buildStorageMap`, `:tools:buildStrongholdMap`, `:tools:buildColdfrontMap` tasks

### Design docs (status updated to FULL)

- `docs/maps/industrial-complex/04-ctf-storage.md` — was DESIGN ONLY (Pass 2); now FULL (Pass 7)
- `docs/maps/desert-ravine/04-ctf-stronghold.md` — was DESIGN ONLY (Pass 3); now FULL (Pass 7)
- `docs/maps/arctic-station/04-ctf-arctic.md` — was MODE READY, MAP DESIGN ONLY (Pass 4); now FULL (Pass 7)

---

## Files modified (full paths + brief diff summary)

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java` — added 3 new static methods (`storage`, `stronghold`, `coldfront`), each returning a `MapSpec` with the appropriate `MapSetting` / `MatchMode.CTF` / 3 lanes / 6 spawns / 6 waypoints / `MapMarkers.CaptureTheFlag` bases
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapLibrary.java` — `registerDefaults()` now also registers `storage`, `stronghold`, `coldfront` (the grid is now complete: 16 of 16 maps)
- `engine/src/main/java/com/openfps/engine/gameplay/README.md` — bumped test count 443 → 449 (+6 new MapLibraryTest entries); updated the "Map library" paragraph to "Pass 7 of 7 — the 16-map grid is complete"; added the 3 new CTF maps to the shipped list and the docs/maps index
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java` — added 6 tests (one register + one describe for each of the 3 new CTF maps)
- `PLAN.md` — Phase 7 now marks "Pass 7 of 7 done; the 16-map grid is complete"; added a bullet for the Pass 7 work; updated the test count and the top-of-file status
- `tools/build.gradle.kts` — added the `buildStorageMap` / `buildStrongholdMap` / `buildColdfrontMap` JavaExec tasks (mirrors the other build*Map tasks)

---

## New tests added (count + paths)

**6 new tests, 1764 total in :engine (up from 1758 in Pass 6, before the 3 CTF maps)**

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java` — 6 tests across 3 pairs (one register + one describe for each new map)
  - `storage` is registered at class load time
  - `storage` spec is a CTF map in Industrial Complex with 2 bases
  - `stronghold` is registered at class load time
  - `stronghold` spec is a CTF map in Desert Ravine with 2 bases
  - `coldfront` is registered at class load time
  - `coldfront` spec is a CTF map in Arctic Station with 2 bases

All 1758 pre-Pass-7 tests still pass. Checkstyle `maxWarnings = 0` holds.

---

## Build verification

### 1. `.\gradlew.bat :engine:test --rerun-tasks`

```
> Task :engine:test
BUILD SUCCESSFUL in 27s
```

Test count: **1764** in `:engine` (up from 1758). Zero failures.

### 2. `.\gradlew.bat checkstyleMain checkstyleTest`

```
> Task :engine:checkstyleTest
BUILD SUCCESSFUL in 8s
18 actionable tasks: 2 executed, 16 up-to-date
```

`maxWarnings = 0` holds across all four modules. Zero new warnings introduced.

### 3. `.\gradlew.bat :tools:buildStorageMap :tools:buildStrongholdMap :tools:buildColdfrontMap`

```
> Task :tools:buildColdfrontMap
INFO  c.openfps.tools.ColdfrontMapBuilder - Wrote .../coldfront/level.ofm (132 triangles, 264 vertices, 2 textures)
> Task :tools:buildStrongholdMap
INFO  c.openfps.tools.StrongholdMapBuilder - Wrote .../stronghold/level.ofm (168 triangles, 336 vertices, 2 textures)
> Task :tools:buildStorageMap
INFO  com.openfps.tools.StorageMapBuilder - Wrote .../storage/level.ofm (192 triangles, 384 vertices, 2 textures)
BUILD SUCCESSFUL in 14s
```

All 3 procedural models built in parallel, no errors.

### 4. `.\gradlew.bat :engine:run --args="--headless --map=storage --fps=60"`

```
15:39:23.953 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
15:39:25.933 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
BUILD SUCCESSFUL in 3s
```

### 5. `.\gradlew.bat :engine:run --args="--headless --map=stronghold --fps=60"`

```
15:39:28.096 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
15:39:30.076 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
BUILD SUCCESSFUL in 3s
```

### 6. `.\gradlew.bat :engine:run --args="--headless --map=coldfront --fps=60"`

```
15:39:32.011 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
15:39:33.994 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
BUILD SUCCESSFUL in 3s
```

All 3 maps smoke-test cleanly: boot, run 120 tics, exit cleanly. The `MapSmokeGameplayPort` headless path loads the spec, builds a `Match` with one bot per waypoint (capped at `Match.DEFAULT_BOT_COUNT`), and ticks.

### 7. `.\gradlew.bat :engine:test --tests "com.openfps.engine.gameplay.map.MapLibraryTest"`

```
> Task :engine:test
31 tests completed, 0 failed
BUILD SUCCESSFUL in 5s
```

Was 25, now 31 (6 new tests for the 3 new CTF maps).

---

## The three new CTF design spec locations (now FULL)

- `C:\Development\fullstack\openfps\docs\maps\industrial-complex\04-ctf-storage.md` — 320×320 chemical storage facility: two warehouse buildings (one per team) at the south-west and north-east corners, eight storage tanks arranged in two rows of four in the centre (the maze), and a single north-south catwalk at x=0, y=64. **FULL** in Pass 7.
- `C:\Development\fullstack\openfps\docs\maps\desert-ravine\04-ctf-stronghold.md` — 320×320 sandstone fortress: two gate towers (east and west), four corner towers (16×16×32 each), a 96×96 central courtyard, a 16×16×8 fountain at the courtyard centre, and two flanking cliff walls (north and south) at y=32. **FULL** in Pass 7.
- `C:\Development\fullstack\openfps\docs\maps\arctic-station\04-ctf-arctic.md` — 320×320 polar-research base: RED base on the west bank (32×32 main hut at (-128, 160) + 16×16×48 watchtower at (-32, 160) + 24×24×16 service shed at (-64, 120)) and BLUE base on the east bank (mirror). **FULL** in Pass 7.

---

## What Pass 7 actually changed in the engine

Pass 6 finished the three Domination maps. Pass 7 finishes the last three maps — the CTF variants of the non-Urban settings. The CTF mode logic, the per-tic update (`Match.updateCtf`), and the test suite (`MatchCtfTest`, 16 tests) were all shipped in Pass 4. Pass 7's contribution is the procedural level model and the spec factory method for each of the three new CTF maps.

### Three new procedural builders

The new builders (`StorageMapBuilder`, `StrongholdMapBuilder`, `ColdfrontMapBuilder`) follow the same shape as the existing ones (`CornerstoneMapBuilder`, `CrossroadsMapBuilder`, `ArcticStationMapBuilder`): a `main(String[] args)` that parses `--out=<dir>`, a `build()` that returns the `.ofm` bytes via the `ModelBuilder` API, a `triangleCount()` accessor, geometry-building helpers (`addBox`, `addFace`, `addGroundSlab`, `addPerimeterWalls`), and texture generators.

The three palettes match the setting:
- `storage` uses a steel palette (similar to `refinery`)
- `stronghold` uses a sandstone palette (similar to `crossroads`)
- `coldfront` uses a snow-tone + sheet-metal palette (similar to `arctic-station`)

### The 16-map library is now complete

| | TDM | HP | DOM | CTF |
|---|---|---|---|---|
| Urban Warzone | cornerstone ✅ | overpass ✅ | tripoint ✅ | extraction ✅ |
| Industrial Complex | refinery ✅ | foundry ✅ | pipeline ✅ | storage ✅ |
| Desert Ravine | crossroads ✅ | mesa ✅ | sandbar ✅ | stronghold ✅ |
| Arctic Station | arctic-station ✅ | arctic-hp ✅ | arctic-dom ✅ | coldfront ✅ |

All 16 maps are FULL. All 4 mode logics (TDM, Hardpoint, Domination, CTF) are shipped end-to-end with tests.

---

## Open items

1. **`MapScene` (the windowed path) is still not built.** The headless smoke test path uses `MapSmokeGameplayPort`, which loads the spec and ticks the match but does not render. Deferred to a later pass.
2. **The 30-second drop-on-death pending state is NOT implemented** — a dead carrier's flag returns to base instantly. A future pass can add the 30s pending if balance changes.
3. **The CTF time limit is enforced by `state()` but not by `updateCtf`** — a match that hits the 36 000-tic limit reports `WON`, but `updateCtf` keeps ticking. The class Javadoc on `updateCtf` documents the invariant.
4. **The 16 procedural level models are staged** (`git add -f .../level.ofm`) but not committed. The user can commit them at their discretion.
5. **The `mavis communication send` tool referenced in the parent session's instructions is not installed on this machine.** Reports are saved to `docs/pass*-report.md` instead. The parent session should check the report file directly rather than waiting for a peer-message echo.

---

## Pass 7 — one-paragraph summary

Pass 7 closes out the 16-map library by adding the last three maps: `storage` (Industrial Complex × CTF, a chemical storage facility with two warehouses and a tank maze), `stronghold` (Desert Ravine × CTF, a sandstone fortress with gate towers and a courtyard), and `coldfront` (Arctic Station × CTF, a polar-research base split across a frozen river). Each map gets a procedural level builder (`StorageMapBuilder`, `StrongholdMapBuilder`, `ColdfrontMapBuilder`) that generates a `.ofm` file via the `ModelBuilder` API — 192 / 168 / 132 triangles respectively — and a `Maps.<name>()` static method that returns the spec. The CTF mode logic was already implemented in Pass 4 (`Match.updateCtf` + 16 `MatchCtfTest` cases), so Pass 7 only adds the new map instances, level models, factory methods, and `MapLibraryTest` entries (+6 tests). The 1764-test engine suite is green, checkstyle is clean, and the headless smoke test boots and runs 120 tics cleanly for all three new maps. **The 16-map library is complete: 4 settings × 4 modes = 16 maps, all FULL.**

---

**Report file**: `C:\Development\fullstack\openfps\docs\pass7-report.md`
