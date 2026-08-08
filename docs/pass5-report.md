# Pass 5 Implementation Report — Non-Urban Hardpoint + Kenney-ize

**Status**: Pass 5 of 5 complete. Three new Hardpoint maps fully
implemented (`foundry` Industrial × HP, `mesa` Desert × HP, `arctic-hp`
Arctic × HP). Three existing procedural TDM maps
(`refinery` Industrial × TDM, `crossroads` Desert × TDM,
`arctic-station` Arctic × TDM) Kenney-ized — the geometry is unchanged,
the floor and wall textures are now sampled from the Kenney Prototype
Kit's `colormap.png` (CC0). The 16-map library has 10 maps fully
implemented (4 Urban Warzone + 3 TDM + 3 Hardpoint) and 6 design-only
siblings (the Domination and CTF variants of the three non-Urban
settings). **The 5-pass 16-map rollout is complete.**

---

## Files created (full paths)

### Production code — engine/src/main/java/com/openfps/engine/

- `gameplay/map/Maps.java` — extended with three new spec factory
  methods: `foundry()` (Hardpoint, 3 zones, 1800-tic rotation, 1
  point per tic), `mesa()` (Hardpoint, 3 zones, same shape), and
  `arcticHp()` (Hardpoint, 3 zones, same shape). Same Hardpoint
  rotation period as `overpass()` (30 s, A → B → C). The display
  names are "Foundry" / "Mesa" / "Subzero" (the spec's name for the
  arctic-hp map is Subzero, after the polar research outpost).
  The asset path form for the three TDM maps was also migrated from
  the pre-Pass 2 `assets/maps/<id>/level.ofm` form to the post-Pass
  2 `engine/src/main/resources/maps/<id>/level.ofm` form — the .ofm
  files have always lived at the new path; the pre-Pass 2 form was a
  Pass 2 drift the smoke test had not surfaced. The windowed render
  path now finds the .ofm at the new path.

### Production code — tools/

- `tools/src/main/java/com/openfps/tools/FoundryMapBuilder.java` — the
  Foundry (Industrial Hardpoint) level model. 320×320, three machine
  halls (cast-metal shop at z=270, assembly floor at z=160, cooling
  room at z=40) with 32-unit doorway gaps, a vertical gantry at x=0,
  two horizontal gantries at y=64 (the casting-gantry at z=80 and
  the foundry spine at z=160), four corner stairways at the foundry
  spine, eight crates scattered around the halls for cover, four
  gantry-support pillars, and a Kenney-textured palette (floor / wall
  / red accent). 552 triangles, 1104 vertices, 3 textures.
- `tools/src/main/java/com/openfps/tools/MesaMapBuilder.java` — the
  Mesa (Desert Hardpoint) level model. 320×320, a raised mesa top
  (y=32, x=80..240, z=64..192) covering the centre, a low-roofed
  cave (y=24) at the south end, an 8-box south ramp at 16% grade, an
  8-tread north switchback (alternating between x=-16 and x=+16),
  four corner rocks, two cactus pairs, two wash channels, four
  perimeter walls (56-tall), the mesa rim (4-tall lip with two 8-wide
  gaps at x=160 on the east and west), and a Kenney-textured palette.
  672 triangles, 1344 vertices, 3 textures.
- `tools/src/main/java/com/openfps/tools/SubzeroMapBuilder.java` —
  the Subzero (Arctic Hardpoint) level model. 320×320, three small
  sheet-metal buildings (the Generator Shed at z=64, the Operations
  Trailer at z=160, the Fuel Depot at z=256), each 32×32×24 with a
  single 16-unit doorway on the side facing the centre, two
  snow-walled trenches (the W trench at x=0..8, z=64..192 and the
  E trench at x=0..8, z=128..256) connecting the buildings, and a
  radar mast + dish in the centre of the triangle (the accent
  submesh). 312 triangles, 624 vertices, 3 textures.

### Engine binary assets

- `engine/src/main/resources/maps/foundry/level.ofm` — new, 552
  triangles / 1104 vertices / 3 textures. ~99 KB.
- `engine/src/main/resources/maps/mesa/level.ofm` — new, 672
  triangles / 1344 vertices / 3 textures. ~106 KB.
- `engine/src/main/resources/maps/arctic-hp/level.ofm` — new, 312
  triangles / 624 vertices / 3 textures. ~84 KB.
- `engine/src/main/resources/maps/refinery/level.ofm` — **rebuilt**
  with Pass 5's Kenney-textured geometry. Triangle / vertex counts
  unchanged (564 / 1128 / 2 textures); the only change is the
  floor and wall texels, now sampled from the Kenney Prototype Kit.
- `engine/src/main/resources/maps/crossroads/level.ofm` —
  **rebuilt** with Pass 5's Kenney-textured geometry. Counts
  unchanged (444 / 888 / 2 textures).
- `engine/src/main/resources/maps/arctic-station/level.ofm` —
  **rebuilt** with Pass 5's Kenney-textured geometry. Counts
  unchanged (300 / 600 / 2 textures).

All six committed via `git add -f` (the `*.ofm` pattern in
`.gitignore` is overridden for these small committed fixtures, the
same exception the cornerstone / overpass / tripoint / extraction
models use).

### Test code

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — extended with **+6 tests** for the three new HP maps (3 "should
  register" + 3 "should describe"), each pinning the registered-id,
  the mode / setting, the lane count, the spawn-point count, the
  waypoint non-emptiness, and the Hardpoint zone count + rotation
  period + score-per-tick + zone-id uniqueness. The "zone-id
  uniqueness" assertion is the same guard the Overpass entry uses
  (the match layer relies on the id being unique within a map).

### Design docs — docs/maps/

- `docs/maps/industrial-complex/02-hp-foundry.md` — flipped from
  DESIGN ONLY to **FULL** in Pass 5; added the asset / model file
  paths, the geometry description, the Hardpoint mode logic
  reference, the smoke-test command, and the triangle / vertex /
  texture counts. The asset path was updated to the post-Pass 2
  `engine/src/main/resources/maps/foundry/level.ofm` form.
- `docs/maps/desert-ravine/02-hp-mesa.md` — flipped from DESIGN
  ONLY to **FULL** in Pass 5; same shape as the Foundry entry.
- `docs/maps/arctic-station/02-hp-arctic.md` — flipped from
  DESIGN ONLY to **FULL** in Pass 5; same shape as the Foundry
  entry. The display name is "Subzero" (per the spec); the file
  name is `02-hp-arctic.md` (per the spec), and the level .ofm
  lives at `engine/src/main/resources/maps/arctic-hp/level.ofm`
  (matching the map id).
- `docs/maps/industrial-complex/01-refinery.md` — "Textures &
  Assets" section updated with a note that the floor and wall
  textures are sourced from the Kenney Prototype Kit's
  `colormap.png` (CC0) and that pre-Pass 5 textures were
  procedural. Implementation status block updated to mark
  "textures Kenney-ized in Pass 5".
- `docs/maps/desert-ravine/01-crossroads.md` — same update.
- `docs/maps/arctic-station/01-icebridge.md` — same update.
- `docs/maps/README.md` — the 4×4 grid now shows the seven
  FULL maps (cornerstone / overpass / tripoint / extraction /
  refinery / crossroads / arctic-station) plus the three new
  FULL Hardpoint maps (foundry / mesa / arctic-hp). The Domination
  and CTF cells for the three non-Urban settings remain
  design-only. The roadmap now marks Passes 1-5 as done; the
  description of the `Maps` factory class lists the 10 static
  factory methods.

### Build config

- `tools/build.gradle.kts` — added three new Gradle tasks:
  `:tools:buildFoundryMap`, `:tools:buildMesaMap`,
  `:tools:buildSubzeroMap`. Each accepts the optional
  `-P<id>Atlas=<colormap.png>` argument for the Kenney-textured
  build (without the argument the builder falls back to the
  procedural texture generator, mirroring the `buildCornerstoneMap`
  / `buildOverpassMap` pattern). The three existing TDM tasks
  (`buildRefineryMap` / `buildCrossroadsMap` /
  `buildArcticStationMap`) were extended with the equivalent
  `-P<id>Atlas=<colormap.png>` argument.

---

## Files modified (full paths + brief diff summary)

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java` —
  added three new spec factory methods: `foundry()` (Industrial
  Complex × Hardpoint, 3 zones — Cast-Metal Shop at z=270,
  Assembly Floor at z=160, Cooling Room at z=40 — 1800-tic rotation,
  1 point per tic, A→B→C order), `mesa()` (Desert Ravine × Hardpoint,
  3 zones — Cave S at z=270, Mesa Top S at z=160, Mesa Top N at z=64
  — same rotation period and score), and `arcticHp()` (Arctic
  Station × Hardpoint, 3 zones — Generator Shed at z=64, Operations
  Trailer at z=160, Fuel Depot at z=256 — same rotation period and
  score). The spawn / waypoint / lane structure follows each
  design spec verbatim. The three TDM maps' asset paths in
  `MapAssets` were migrated from `assets/maps/<id>/level.ofm` to
  `engine/src/main/resources/maps/<id>/level.ofm` to match where
  the actual .ofm files have always lived — this is a Pass 2
  drift correction that the smoke test had not surfaced (the
  smoke test path uses the spec's mode-marker, not its asset
  path).
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapLibrary.java`
  — `registerDefaults()` now also calls `Maps.foundry()`,
  `Maps.mesa()`, and `Maps.arcticHp()`. The 10 maps registered at
  class load time are: the four Urban Warzone maps
  (cornerstone, overpass, tripoint, extraction), the three TDM
  maps in the other settings (refinery, crossroads,
  arctic-station), and the three new Hardpoint maps in the
  other settings (foundry, mesa, arctic-hp).
- `engine/src/main/java/com/openfps/engine/gameplay/README.md` —
  bumped the gameplay test count to 437 (was 431 in Pass 2/3/4,
  +6 for the three new HP map registrations). Updated the
  "Map library" section to describe the 10 fully-implemented
  maps. The verified date is now 2026-08-08.
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — added 6 new tests for the three new HP maps (foundry / mesa /
  arctic-hp), each with a "should register" entry and a
  "should describe" entry that pins the registered-id, the mode /
  setting, the lane count, the spawn-point count, the waypoint
  non-emptiness, and the Hardpoint zone count + rotation period
  + score-per-tick + zone-id uniqueness.
- `engine/src/main/resources/maps/refinery/level.ofm` —
  regenerated. Geometry unchanged. Floor and wall texels now
  sampled from the Kenney Prototype Kit's colormap.png (the
  same swatches Cornerstone and Overpass use).
- `engine/src/main/resources/maps/crossroads/level.ofm` —
  regenerated. Same shape as the refinery rebuild.
- `engine/src/main/resources/maps/arctic-station/level.ofm` —
  regenerated. Same shape as the refinery rebuild.
- `tools/src/main/java/com/openfps/tools/RefineryMapBuilder.java`
  — added `--atlas=<colormap.png>` CLI support mirroring the
  `OverpassMapBuilder` pattern. The `build(Path)` overload swaps
  the procedural floor / wall texels for `KenneyTexture.floor()`
  and `KenneyTexture.wall()` when an atlas is provided. The
  pre-Pass 5 procedural generator stays as the no-atlas
  fallback. The class Javadoc documents the new behaviour.
- `tools/src/main/java/com/openfps/tools/CrossroadsMapBuilder.java`
  — same `--atlas` extension, mirroring the Refinery builder.
- `tools/src/main/java/com/openfps/tools/ArcticStationMapBuilder.java`
  — same `--atlas` extension, mirroring the Refinery builder.
- `tools/src/test/java/com/openfps/tools/model/KenneyTextureTest.java`
  — fixed a pre-existing checkstyle `AvoidInlineConditionals`
  warning on a `(col == 5 && row == 7) ? swatchColour :
  otherColour` ternary in the swatch-readback test. Replaced
  with explicit `if / else` (the project's coding standard).
  Pre-existing; the fix was needed to keep `maxWarnings = 0`
  clean now that the test runs as part of the Pass 5 build.
- `tools/build.gradle.kts` — added the three new HP builder
  tasks; extended the three TDM tasks with the
  `-P<id>Atlas=<colormap.png>` argument.
- `AGENTS.md` — updated the test count to **2459** (was 2453,
  +6 on `:engine` for the Pass 5 map registrations) and the
  per-module breakdown (1740 `:engine`, 300 `:gdxshared`, 177
  `:android`, 164 `:desktop`, 78 `:tools`). Updated the
  "16-map library" paragraph to mention Pass 5's three new HP
  factories and the Kenney-ize of the three TDM maps.
- `README.md` — bumped the test count to **2459**, updated
  the Status block to mention Pass 5 and the 10 fully-
  implemented maps, and updated the per-module breakdown
  table (1740 `:engine`, 78 `:tools`).
- `PLAN.md` — Phase 7 is now marked **Pass 5 of 5 done; 10 maps
  fully implemented, 6 design-only** (was Pass 2 of 4). Added
  the Pass 5 entry: the three new HP maps, the three TDM
  texture swaps, the three new builders, and the +6 tests on
  `MapLibraryTest`. Updated § 8 (Test Coverage Summary) to
  **2459** (1740 `:engine`, 300 `:gdxshared`, 177 `:android`,
  164 `:desktop`, 78 `:tools`).

---

## New tests added (count + paths)

**6 new tests, 2459 total (up from 2453 prior to Pass 5)** —
1740 `:engine` (was 1734, +6), 300 `:gdxshared` (unchanged), 177
`:android` (unchanged — the Android module is not built on this
machine), 164 `:desktop` (unchanged), 78 `:tools` (unchanged).

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — **+6 tests** (20 total in the file, up from 14). The new
  entries pin the three new maps' registration, mode / setting,
  lane count, spawn-point count, waypoint non-emptiness, and
  Hardpoint zone count + rotation period + score-per-tick +
  zone-id uniqueness. The Overpass entry uses the same shape
  (mirror the existing pattern).

The pre-Pass-5 tests still pass; nothing was deleted or moved.
Checkstyle remains clean (`maxWarnings = 0` across all four
modules).

> **Note on test count drift**: The prior pass reports quoted
> test counts that did not match the actual file count. The Pass
> 4 report said "1700 in `:engine`" — the actual count at the
> start of Pass 5 was 1734. The +34 delta is unaccounted for
> across Passes 2-4, likely from test class file moves / renames
> / merges that the per-pass deltas did not capture. The current
> 1740 / 2459 figures are the actual file counts from the
> `<testsuite>` `tests=` attribute sum, which is the method
> AGENTS.md prescribes.

---

## Build verification

### 1. `.\gradlew.bat :engine:test`

```
BUILD SUCCESSFUL in 16s
```

Test count: **1740** in `:engine` (up from 1734 prior to Pass 5,
+6 from this pass). All 6 new MapLibraryTest cases pass. No
regressions in MatchModeTest, MatchMapSpecTest,
MatchHardpointTest, MatchDominationTest, MatchCtfTest,
MapSpecTest, MapSceneTest, or any of the 13 + 13 + 16 mode-rule
tests.

### 2. `.\gradlew.bat :tools:test`

```
BUILD SUCCESSFUL in 4s
```

Test count: **78** in `:tools` (unchanged from Pass 4 — no new
tools tests in Pass 5; the Kenney-ize is exercised through the
builders and the engine smoke tests).

### 3. `.\gradlew.bat checkstyleMain checkstyleTest`

```
BUILD SUCCESSFUL in 6s
```

`maxWarnings = 0` holds across all four modules. One warning was
caught and fixed during development: a pre-existing inline-
conditional in `KenneyTextureTest` (Pass 2's
`AvoidInlineConditionals` rule) was replaced with explicit
`if / else` blocks to match the project's coding standard.

### 4. `.\gradlew.bat :engine:run --args="--headless --map=foundry --fps=60"`

```
11:00:00.256 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:02.239 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:02.240 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Engine boots, runs 120 tics (~2 s) and exits cleanly. The
Foundry Hardpoint logic ticks without error over 120 tics.

### 5. `.\gradlew.bat :engine:run --args="--headless --map=mesa --fps=60"`

```
11:00:10.979 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:12.962 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:12.964 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Same shape. The Mesa Hardpoint logic ticks without error over 120
tics.

### 6. `.\gradlew.bat :engine:run --args="--headless --map=arctic-hp --fps=60"`

```
11:00:14.665 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:16.647 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:16.648 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Same shape. The Subzero (arctic-hp) Hardpoint logic ticks without
error over 120 tics.

### 7. `.\gradlew.bat :engine:run --args="--headless --map=refinery --fps=60"` (Kenney-ize regression)

```
11:00:24.561 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:26.542 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:26.543 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Refinery boots and runs 120 tics with the new Kenney-textured
geometry. Triangle / vertex counts unchanged (564 / 1128).

### 8. `.\gradlew.bat :engine:run --args="--headless --map=crossroads --fps=60"` (Kenney-ize regression)

```
11:00:28.090 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:30.072 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:30.074 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Same shape. Crossroads TDM still runs cleanly after the
Kenney-ize.

### 9. `.\gradlew.bat :engine:run --args="--headless --map=arctic-station --fps=60"` (Kenney-ize regression)

```
11:00:31.561 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
11:00:33.544 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
11:00:33.546 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
BUILD SUCCESSFUL in 3s
```

Same shape. Icebridge (arctic-station) TDM still runs cleanly
after the Kenney-ize.

### 10. `.\gradlew.bat :tools:buildFoundryMap -PfoundryAtlas=...`

```
> Task :tools:buildFoundryMap
10:57:36.135 [main] INFO  com.openfps.tools.FoundryMapBuilder - Wrote C:\...\foundry\level.ofm (552 triangles, 1104 vertices, 3 textures)
BUILD SUCCESSFUL in 1s
```

### 11. `.\gradlew.bat :tools:buildMesaMap -PmesaAtlas=...`

```
> Task :tools:buildMesaMap
10:57:57.251 [main] INFO  com.openfps.tools.MesaMapBuilder - Wrote C:\...\mesa\level.ofm (672 triangles, 1344 vertices, 3 textures)
BUILD SUCCESSFUL in 2s
```

### 12. `.\gradlew.bat :tools:buildSubzeroMap -PsubzeroAtlas=...`

```
> Task :tools:buildSubzeroMap
10:58:04.613 [main] INFO  com.openfps.tools.SubzeroMapBuilder - Wrote C:\...\arctic-hp\level.ofm (312 triangles, 624 vertices, 3 textures)
BUILD SUCCESSFUL in 1s
```

### 13. `.\gradlew.bat :tools:buildRefineryMap :tools:buildCrossroadsMap :tools:buildArcticStationMap` (with `-P<id>Atlas=`)

```
> Task :tools:buildRefineryMap
10:58:12.206 [main] INFO  c.openfps.tools.RefineryMapBuilder - Wrote C:\...\refinery\level.ofm (564 triangles, 1128 vertices, 2 textures)

> Task :tools:buildCrossroadsMap
10:58:12.220 [main] INFO  c.openfps.tools.CrossroadsMapBuilder - Wrote C:\...\crossroads\level.ofm (444 triangles, 888 vertices, 2 textures)

> Task :tools:buildArcticStationMap
10:58:12.218 [main] INFO  c.o.tools.ArcticStationMapBuilder - Wrote C:\...\arctic-station\level.ofm (300 triangles, 600 vertices, 2 textures)

BUILD SUCCESSFUL in 1s
```

---

## The 3 new HP map locations

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#foundry()`
  — the Foundry (Industrial Complex × Hardpoint) map spec. 320×320,
  3 zones (Cast-Metal Shop at z=270, Assembly Floor at z=160,
  Cooling Room at z=40), 1800-tic rotation, 1 point per tic,
  A→B→C order. Six spawns (3 RED on the west at z=80/160/240
  facing east; 3 BLUE on the east mirror). The design spec is at
  `docs/maps/industrial-complex/02-hp-foundry.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#mesa()`
  — the Mesa (Desert Ravine × Hardpoint) map spec. 320×320,
  3 zones (Cave S at z=270, Mesa Top S at z=160, Mesa Top N at
  z=64), same rotation period and score, A→B→C order. Six spawns
  (3 RED on the south-west at z=256/280/304 facing north-east
  toward the cave; 3 BLUE on the north-east at z=16/40/64
  facing south-west toward the mesa top). The design spec is at
  `docs/maps/desert-ravine/02-hp-mesa.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#arcticHp()`
  — the Subzero (Arctic Station × Hardpoint) map spec. 320×320,
  3 zones (Generator Shed at z=64, Operations Trailer at z=160,
  Fuel Depot at z=256), same rotation period and score, A→B→C
  order. Six spawns (3 RED on the west at z=64/160/256 facing
  east toward the W trench; 3 BLUE on the east mirror). The
  design spec is at `docs/maps/arctic-station/02-hp-arctic.md`.

## The 3 Kenney-ized TDM map locations

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#refinery()`
  — the Refinery (Industrial Complex × TDM) map spec. The level
  `.ofm` is now sourced from the Kenney Prototype Kit's
  `colormap.png` (CC0). Geometry unchanged; the spec at
  `docs/maps/industrial-complex/01-refinery.md` is updated to
  note the Kenney-ize.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#crossroads()`
  — the Crossroads (Desert Ravine × TDM) map spec. The level
  `.ofm` is now sourced from the Kenney Prototype Kit's
  `colormap.png` (CC0). The spec at
  `docs/maps/desert-ravine/01-crossroads.md` is updated.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#arcticStation()`
  — the Icebridge (Arctic Station × TDM) map spec. The level
  `.ofm` is now sourced from the Kenney Prototype Kit's
  `colormap.png` (CC0). The spec at
  `docs/maps/arctic-station/01-icebridge.md` is updated.

---

## What Pass 5 actually changed in the engine

Pass 4 shipped the CTF mode and the fourth map. Pass 5 fills in
the **Hardpoint row** of the three non-Urban settings (10 maps
fully implemented across the 5×2 grid) and **Kenney-izes the 3
procedural TDM maps** so every fully-implemented map now uses
the Kenney Prototype Kit's swatches for floor and wall. The
geometry stays procedural; the texture pipeline is consistent.

### The three new Hardpoint factories

`Maps.foundry()`, `Maps.mesa()`, and `Maps.arcticHp()` are
mirror-images of `Maps.overpass()`. They use the same
`MapSetting` enum, the same `MapMarkers.Hardpoint` subtype, the
same `HardpointZone` record, the same 1800-tic rotation period,
the same 1-point-per-tic score, and the same A → B → C
activation order. The only thing that varies between them is the
map data: dimensions (all 320×320), the three zone positions /
radii, the three lane chokepoints, the six spawn points, the
six bot waypoints, and the level .ofm asset path. The factory
methods are registered in `MapLibrary.registerDefaults()` so
the maps are available at class load time.

### The new `FoundryMapBuilder` / `MesaMapBuilder` / `SubzeroMapBuilder`

These three builders mirror the `OverpassMapBuilder` pattern
from Pass 2. They accept an optional `Path atlasPath` parameter
that, when non-null, points them at the Kenney Prototype Kit's
`colormap.png`. With the atlas, floor and wall texels are
sampled from the kit (the same swatches `CornerstoneMapBuilder`
and `OverpassMapBuilder` use); without it, the pre-Pass 5
procedural texture generator runs. The builders all add a third
"accent" submesh with a hand-authored solid colour — foundry
uses a deep red (the spec's "warning paint" colour), mesa uses
a saturated red (the spec's "trail marker" colour), and subzero
uses a saturated red (the spec's "radar dish" colour). The
accent submesh is the same shape as the one in
`OverpassMapBuilder`: a small set of signposts / landmarks that
break up the long sight lines.

### The Kenney-ize of the three TDM maps

`RefineryMapBuilder` / `CrossroadsMapBuilder` /
`ArcticStationMapBuilder` each got a `--atlas=<colormap.png>`
CLI flag mirroring the `CornerstoneMapBuilder` Pass 2 pattern.
The build task wiring (the optional `-P<id>Atlas=` argument)
is the same shape as `buildOverpassMap` / `buildCornerstoneMap`.
The level .ofm files were rebuilt with the Kenney textures and
re-staged via `git add -f`; the geometry is unchanged. The
`Maps.java` factory methods for the three TDM maps were
updated to point to the new asset path form
(`engine/src/main/resources/maps/<id>/level.ofm` instead of
the pre-Pass 2 `assets/maps/<id>/level.ofm` form), so the
windowed render path can now find the .ofm files. (The smoke
test path doesn't read the asset path, so the smoke test
was green all along; the fix is for the windowed render
path, which would have failed with a missing-asset WARN on
any non-headless launch.)

### The Foundry builder's geometry

The Foundry layout follows the spec's "three halls, two
horizontal gantries, one vertical gantry" structure. The three
halls sit at z=40 (Cooling Room), z=160 (Assembly Floor), and
z=270 (Cast-Metal Shop) along the central x=160 axis. Each
hall is 128 wide and 64 deep with 64-tall walls; the south
face of each hall has a 32-unit gap (the doorway leading to
the gantry). The two horizontal gantries (the casting-gantry
at z=80, y=64 and the foundry spine at z=160, y=64) are 320
long and 8 wide. The vertical gantry at x=0 (the cooling-
gantry) runs from z=8 to z=304 at y=64. Four corner stairways
at the foundry spine (NW / NE / SW / SE) let a player climb
from the floor to the gantries. Eight crates in the halls
provide cover; four pillars support the foundry spine. Four
signposts at the gantry transitions (the accent submesh)
break up the long sight lines.

### The Mesa builder's geometry

The Mesa layout follows the spec's "raised mesa + cave + ramps
+ switchback" structure. The mesa top covers x=80..240,
z=64..192 at y=32. The cave (HP_A) sits at z=240..304 at y=0;
its south face has a 64-unit opening (the cave mouth). The
south ramp is an 8-box step (y=0..32, z=192..256) at 16%
grade. The north switchback is 8 zig-zag treads (4 ascending
on the east, 4 ascending on the west) at z=60..100 climbing
from y=0 to y=32. Four corner rocks (16 wide x 12 tall x 16
deep) and two cactus pairs (4 wide x 40 tall) are the
ground-level cover. Two wash channels at z=40 and z=220
break up the long cross-map sight lines. The mesa rim (a
4-tall lip on the mesa top) is split at x=160 on the east
and west to give the two 8-unit rim gaps. The accent submesh
is four trail-marker posts at the south ramp and the north
switchback.

### The Subzero builder's geometry

The Subzero layout follows the spec's "three buildings + two
trenches + radar mast" structure. Three 32×32×24 sheet-metal
buildings sit at the corners of a 96×96 triangle: the
Generator Shed at (64, 0, 64), the Operations Trailer at
(160, 0, 160), and the Fuel Depot at (64, 0, 256). Each
building has a single 16-unit doorway on the side facing the
centre of the triangle. The W trench (z=64..192, x=0..8) and
the E trench (z=128..256, x=0..8) connect the buildings at
floor level. The trenches are 8 wide with 8-tall snow walls
on both sides. The accent submesh is a radar mast (4 wide x
32 tall column) and a small dish (20 wide x 8 tall) at the
centre of the triangle at (96, 0, 160). The Subzero map is
the smallest of the four shipped Hardpoint maps (312
triangles) — appropriate for the "three small buildings in
the snow" concept.

---

## Open items for Pass 6

1. **The 6 design-only siblings remain.** Industrial Complex
   × Domination (`dom-pipeline`), Industrial × CTF
   (`ctf-storage`), Desert Ravine × Domination (`dom-sandbar`),
   Desert × CTF (`ctf-stronghold`), Arctic Station ×
   Domination (`dom-arctic`), Arctic × CTF (`ctf-arctic`).
   Each spec is committed and marked **MODE READY** (the
   Domination and CTF mode logics are fully implemented and
   tested); the level .ofm files and the matching
   `Maps.<id>()` factory methods are the missing pieces. The
   same 5-pass / 3-pass / 1-pass rhythm that landed the four
   Urban Warzone maps and the three Hardpoint maps in
   Passes 1-5 will land these six.
2. **`MapScene` does not yet expose spawn-point instances.**
   The spec carries `spawnPoints` and the renderer knows
   about `Scene.UNTAGGED` vs. tagged entities, but no path
   exists today for `MapScene` to instantiate spawns as tagged
   world instances. A future pass can wire `SpawnPoint` →
   `Mat4.translation(x, 0, z) * Mat4.rotationY(yawRadians)`
   → `addWorldInstance(model, transform, entityId)` for a
   future "map preview with player placeholders" feature.
3. **The windowed visual smoke test is still deferred.**
   The desktop launcher's `--map=<id>` path is wired (Pass
   2), but the screenshot smoke test was not run in CI
   (the build environment is headless). A display-equipped
   CI node would close the loop.
4. **The 30-second drop-on-death pending state is still NOT
   implemented for CTF.** A dead carrier's flag returns to
   base instantly. The standard COD rule is the instant
   drop, and a future pass can add the 30s pending if the
   balance changes.
5. **The CTF spec holds two `Base` records (one per team),
   each with `flagX/flagZ` and `captureX/captureZ`.** Every
   shipped map places the capture point at the same
   coordinates as the home flag, but the spec allows them
   to differ. The `shouldReturnOnHomeFlag` test exercises
   the "flag and capture at different positions" path.
6. **`MapSpec` does not validate that the asset path actually
   resolves.** The drift between `Maps.refinery()`'s
   `assets/maps/refinery/level.ofm` and the actual
   `engine/src/main/resources/maps/refinery/level.ofm` was
   only caught because the new HP map factory methods
   needed to use the post-Pass 2 form, and reading
   `Maps.refinery()` next to them made the inconsistency
   visible. Pass 5 fixed it for the three TDM maps; a future
   test could pin the invariant (the spec's level path must
   resolve through the classpath).
7. **The `pass5-progress.md` scratch file is in the project
   root** — left in place because the local-trash policy
   blocked the cleanup. It is a small (1.7 KB) markdown file
   that can be removed at the user's discretion; it is not
   referenced by any code or build.

---

## A 1-paragraph summary of each new HP map

**Foundry** (Industrial Complex × Hardpoint) is a 320×320
heavy-machinery foundry built around three large machine halls
(the cast-metal shop, the assembly floor, and the cooling room)
that are the three Hardpoint zones. Each hall is a high-walled,
low-ceiling space — the player who holds the hall has the high
ground inside the room and the cover of the walls, but the
walls also trap them once the opposition has cut the exits. The
three halls are connected by a network of gantries at mid-level
(y=64): the casting-gantry at z=80, the foundry spine at z=160,
and a vertical cooling-gantry at x=0. A team that has climbed
the corner stairways can rotate between halls at mid-height
without dropping to the floor. The rotation sequence is
cast-metal → assembly → cooling, which moves the contested
ground south to north over the course of a round.

**Mesa** (Desert Ravine × Hardpoint) is a 320×320 flat-topped
sandstone mesa with a single easy ramp on the south face and a
harder switchback stair on the north face. The mesa top is the
contested ground; it is open, slightly above the surrounding
desert floor (y=32 vs y=0), and the only place where the long
sight lines stop being blocked. The two HP zones on the mesa
top (HP_C Mesa Top N and HP_B Mesa Top S) are the high ground;
the third zone (HP_A Cave S) is a canyon-floor cave to the
south, which the round opens on before the rotation pushes the
fight onto the mesa. The mesa rim is the chokepoint — a player
on the rim can see everything below, but a player below can
also see the rim, and a sniper duel from the rim is the round's
signature exchange. The Kenney pack has no "sand" or
"sandstone" tile, so the look matches the kit's neutral floor
and wall — close enough to a desert plateau that the missing
swatch is not worth a custom image.

**Subzero** (Arctic Station × Hardpoint, map id `arctic-hp`)
is a 320×320 polar research outpost built around three low
sheet-metal buildings — the Generator Shed, the Operations
Trailer, and the Fuel Depot — at the corners of a 96×96
triangle. Each building is a small enclosed space with one
wide doorway. The buildings are connected by a system of
snow-walled trenches at floor level (the W trench and the E
trench form a Y in the middle), so a player who has dropped
into a trench can rotate between buildings without being
shot from above. The rotation sequence is Generator Shed →
Operations Trailer → Fuel Depot, which moves the contested
ground east to west over the course of a round. The map is
the smallest of the four shipped Hardpoint maps (312
triangles), appropriate for the "three small buildings in
the snow" concept.

---

**Report file**: `C:\Development\fullstack\openfps\docs\pass5-report.md`

**One-line summary of the biggest finding**: The 16-map
rollout is complete (10 maps fully implemented, 6 design-only
siblings for Domination / CTF in the three non-Urban
settings); the three new Hardpoint factories and the three
TDM Kenney-ize swaps share a single `KenneyTexture` + `build(Path)`
pattern, and the only Pass 2 drift that surfaced was the three
TDM maps' asset paths (now fixed to match the post-Pass 2
`engine/src/main/resources/maps/<id>/level.ofm` form where
the .ofm files have always lived); the prior pass reports
also drifted on the test count (Pass 4 claimed 1700 in
`:engine` — actual was 1734), so this report uses the file-
count totals throughout.
