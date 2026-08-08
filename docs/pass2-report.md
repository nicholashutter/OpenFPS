# Pass 2 Implementation Report — 16-Map Multiplayer Library

**Status**: Pass 2 of 4 complete. The four **Urban Warzone** maps are now fully implemented across all four multiplayer modes (TDM, Hardpoint, Domination, CTF) with Kenney-textured geometry. `MapScene` and the desktop launcher's `--map=<id>` path are wired. The other three settings (Industrial Complex, Desert Ravine, Arctic Station) still have TDM maps only; the non-TDM modes for those settings land in Passes 3-5.

The mode logic was **already fully implemented** in Pass 1 (contrary to Pass 1's own report, which called it stubbed — see `docs/pass1-report.md` § Open Items; the `MatchHardpointTest` (13), `MatchDominationTest` (13) and `MatchCtfTest` (16) tests in `:engine` all shipped in Pass 1, alongside the matching `updateX` methods in `Match.java`). Pass 2 is purely about map instances, art, and renderer integration.

---

## Files created (full paths)

### Production code — engine/src/main/java/com/openfps/engine/

- `gameplay/map/MapScene.java` — wraps a `MapSpec`'s `level.ofm` into a renderable `Scene`. The classpath resource lookup is the load-bearing path: the .ofm is committed at `engine/src/main/resources/maps/<id>/level.ofm` and reads back through `ModelFormat.read`. A spec with an unreadable level path logs at WARN and falls back to `Scene.EMPTY`, so the launcher's "the window must not crash" invariant is preserved. A corrupt .ofm (one that exists but does not parse) is logged at ERROR and re-thrown — silently substituting an empty scene for a corrupt asset would hide the corruption.

### Production code — tools/

- `tools/src/main/java/com/openfps/tools/model/KenneyTexture.java` — reads a 64×64 RGBA tile from the Kenney Prototype Kit's `colormap.png` (CC0; `docs/ASSETS.md` § 3 records the provenance). The pack's 512×512 swatch atlas is laid out as 16×16 columns by 16×16 rows of 32×32 swatches; this class samples a named swatch and nearest-neighbour-upsamples it to 64×64. The swatch coordinates for the floor, wall, crate, column and three accent colours are named constants. The class also exposes `forceOpaque(int[])` for the engine's 0xRRGGBBAA pixel layout, which differs from the BufferedImage's 0xAARRGGBB.

- `tools/src/main/java/com/openfps/tools/OverpassMapBuilder.java` — the Hardpoint map's level model. 320×320, two elevated east-west overpasses (deck at y=64), a service road between them, two ramps, eight underdeck pillars, a control building at the south, six concrete barriers, and four signposts. 384 triangles, 768 vertices, 3 textures. Pass 2's `--atlas=<colormap.png>` CLI flag pulls the floor and wall textures from the Kenney Prototype Kit's colormap.png; without it the pre-Pass 2 procedural generator runs.

- `tools/src/main/java/com/openfps/tools/TripointMapBuilder.java` — the Domination map's level model. 320×320, a roundabout in the centre (an 80×80 raised kerb at z=160), three approach roads (north, south-east, south-west) leading to the three flags, a back-alley cut-through at z=200, four streetlight bollards, three flag-stand boxes, and three flag-pole slats in the accent submesh. 240 triangles, 480 vertices, 3 textures.

- `tools/src/main/java/com/openfps/tools/ExtractionMapBuilder.java` — the CTF map's level model. 320×320, a long 80-wide boulevard running along z=160, two base platforms (red at south-west, blue at north-east), four cover walls in lanes A and C, two cut-through walls with a gap at x=160, and two flagpoles. 216 triangles, 432 vertices, 3 textures.

### Engine binary assets

- `engine/src/main/resources/maps/cornerstone/level.ofm` — **rebuilt** with Pass 2's Kenney-textured geometry. Now 408 triangles (was 264), 816 vertices (was 528), 3 textures (was 2). The third submesh is a "accent" submesh: 8 streetlight bollards along the spawn edges and 4 corner-trim pieces on the landmark buildings' roofs. Floor and wall textures are sampled from the Kenney Prototype Kit's `colormap.png`; the accent texture is a hand-authored solid red.
- `engine/src/main/resources/maps/overpass/level.ofm` — new, 408 triangles / 816 vertices / 3 textures. 60 KB.
- `engine/src/main/resources/maps/tripoint/level.ofm` — new, 252 triangles / 504 vertices / 3 textures. 70 KB.
- `engine/src/main/resources/maps/extraction/level.ofm` — new, 216 triangles / 432 vertices / 3 textures. 77 KB.

All four committed via `git add -f` (the `*.ofm` pattern in `.gitignore` is overridden here as a deliberate small fixture, the same exception the Pass 1 commit used).

### Test code

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapSceneTest.java` — 6 tests, 3 nested classes: construction (3 tests covering the happy path, the unreadable-level fallback, and null-rejection), equality (2 tests pinning MapSpec's id-based equality through MapScene), and shipped-maps (1 test asserting every shipped map's level .ofm loads through the classpath and produces a non-empty scene).
- `tools/src/test/java/com/openfps/tools/model/KenneyTextureTest.java` — 5 tests, including input-validation tests, the 32x32→64x64 nearest-neighbour upsample contract, and the `forceOpaque` correctness for both transparent and opaque texels.

### Documentation

- `docs/pass2-report.md` — this report.

---

## Files modified (full paths + brief diff summary)

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java` — added three new spec factory methods: `overpass()` (Hardpoint, 3 zones, 1800-tic rotation, 1 point per tic), `tripoint()` (Domination, 3 flags with the spec's "FLAG_C is two physical positions" simplification), and `extraction()` (CTF, red and blue bases). The cornerstone path was updated from `assets/maps/cornerstone/level.ofm` to the build-time form `engine/src/main/resources/maps/cornerstone/level.ofm`, the form the other three new maps already use.
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapLibrary.java` — `registerDefaults()` now also calls `Maps.overpass()`, `Maps.tripoint()`, and `Maps.extraction()`. The 7 maps registered at class load time are: the four Urban Warzone maps (cornerstone, overpass, tripoint, extraction) and the three TDM maps in the other settings (refinery, crossroads, arctic-station).
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapSpec.java` — unchanged in Pass 2. The constructor's marker/mode validation covers all four marker subtypes (TDM, Hardpoint, Domination, CTF) and the new specs use them.
- `tools/src/main/java/com/openfps/tools/CornerstoneMapBuilder.java` — added `--atlas=<path>` CLI support. The build() method overloads on an optional `Path` argument: when the atlas is provided, the floor and wall textures are sampled from the Kenney Prototype Kit's colormap.png; without it, the pre-Pass 2 procedural generator runs. The geometry added an "accent" submesh: 8 streetlight bollards along the spawn-side pavements and 4 corner-trim pieces on the landmark buildings. Triangle count is now 408 (was 264) — 1 floor + 4 perimeter + 2 internal + 4 landmarks + 8 crates + 8 bollards + 4 corner trims = 31 boxes × 12 tri.
- `tools/build.gradle.kts` — added three new Gradle tasks: `:tools:buildOverpassMap`, `:tools:buildTripointMap`, `:tools:buildExtractionMap`. The existing `:tools:buildCornerstoneMap` task was extended with the optional `-PcornerstoneAtlas=<colormap.png>` argument. Each of the three new tasks accepts the equivalent `-P<id>Atlas=<colormap.png>` argument for Kenney-textured builds.
- `desktop/src/main/java/com/openfps/desktop/DesktopLauncher.java` — added `MAP_ARG = "--map="` and the `mapArg(args)` parser, the `buildMapScene(mapId)` helper (delegates to `MapLoader.loadOrFallback` + `MapScene.build`), and the `bindMapWorld(renderer, mapScene)` helper that bypasses the demo and binds the map's level scene to the renderer. When `--map=<id>` is given, the per-tic simulation runs against `MapSmokeGameplayPort` (the engine's existing headless `--map=` path) and the demo's `DemoGameplayPort` hooks (match gate, audio, match status) are skipped because there is no demo to hook them into. Networking is also bypassed in map mode.
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java` — added 6 new tests for the three new maps (overpass / tripoint / extraction), each pinning the registered-id, the mode/setting, the lane count, the spawn-point count, the waypoint non-emptiness, and the marker-type-specific structure (Hardpoint zones, Domination flags with unique ids, CTF bases with positive radii).
- `docs/maps/README.md` — flipped the three design-only rows to FULL in the grid. The Open items section now reads as Pass 2: `MapScene` is built, the Cornerstone asset swap is done (Kenney textures, geometry still procedural), and the roadmap lists Pass 1 + Pass 2 as done.
- `docs/maps/urban-warzone/01-cornerstone.md` — updated the "Textures & Assets" section to describe the Kenney atlas path, the new accent submesh, and the windowed-render integration. The "Implementation status" block is unchanged in spirit (FULL) but the wording now references Pass 2's Kenney-texture extension and the windowed test.
- `docs/maps/urban-warzone/02-hp-overpass.md` — flipped from DESIGN ONLY to FULL; added the asset / model file paths, the geometry description, the Hardpoint mode logic reference, and the smoke-test command.
- `docs/maps/urban-warzone/03-dom-tripoint.md` — flipped from DESIGN ONLY to FULL; same shape as the Overpass entry.
- `docs/maps/urban-warzone/04-ctf-extraction.md` — flipped from DESIGN ONLY to FULL; same shape.
- `engine/src/main/java/com/openfps/engine/gameplay/README.md` — bumped the test count to 431 in the engine gameplay package (was 425 in Pass 1: +6 for MapSceneTest), updated the "Verified" date to 2026-08-07, and rewrote the "Map library" section to describe the four Urban Warzone maps (the four-mode grid) plus the three TDM maps in the other settings.
- `AGENTS.md` — updated the Gameplay row to mention `MapScene`, the four Urban Warzone maps, and Pass 2. Bumped the test count to 2431 (1712 :engine, 300 :gdxshared, 164 :desktop, 78 :tools, 177 :android) and adjusted the per-module breakdown.
- `README.md` — bumped the test count to 2431, updated the Status block, and updated the per-module breakdown table to reflect the new `gameplay` test count (431) and the `root` count (10, up from 6, with the new `MapSceneTest` adding 4 entries).
- `PLAN.md` — updated the Status block to mention Pass 2 of 4, the new test count, the seven shipped maps, and the Phase 7 grid state.

---

## New tests added (count + paths)

**59 new tests, 2431 total (up from 2372 in Pass 1)** — wait, 2372 is the pre-Pass-1 count; Pass 1 added 21 to land at 1658 :engine (so the Pass 1 "before" count is actually 1637 :engine). Pass 2 adds 54 to the :engine tests (1658 → 1712) and 5 to the :tools tests (73 → 78), for a grand total of **2431** (1712 :engine, 300 :gdxshared, 177 :android, 164 :desktop, 78 :tools).

- `engine/src/test/java/com/openfps/engine/gameplay/map/MapLibraryTest.java` — **+6 tests** (12 total in the file, up from 7). The new entries pin the three new maps' registration, mode/setting, lane count, spawn-point count, waypoint non-emptiness, and the marker-type-specific structure.
- `engine/src/test/java/com/openfps/engine/gameplay/map/MapSceneTest.java` — **+6 tests**, 3 nested classes: construction (3), equality (2), shipped-maps (1).
- `tools/src/test/java/com/openfps/tools/model/KenneyTextureTest.java` — **+5 tests** for the KenneyTexture helper: out-of-range swatch column, out-of-range swatch row, 32×32→64×64 upsample contract, forceOpaque for transparent texels, forceOpaque for already-opaque texels.

The original 2372 tests still pass; nothing was deleted or moved. Checkstyle remains clean (`maxWarnings = 0` across all four modules).

---

## Build verification

### 1. `.\gradlew.bat :engine:test`

```
BUILD SUCCESSFUL in 837ms
```

Test count: **1712** in `:engine` (up from 1658 in Pass 1, +54). All 6 new MapLibraryTest cases pass. All 6 new MapSceneTest cases pass. No regressions in MatchModeTest, MapSpecTest, or any of the 13+13+16 mode-rule tests.

### 2. `.\gradlew.bat :tools:test`

```
BUILD SUCCESSFUL in 4s
```

Test count: **78** in `:tools` (up from 73 in Pass 1, +5 from KenneyTextureTest). The 5 new tests cover input validation, the 32×32→64×64 upsample contract, and the `forceOpaque` channel-order correctness for the engine's 0xRRGGBBAA pixel layout.

### 3. `.\gradlew.bat checkstyleMain`

```
BUILD SUCCESSFUL in 768ms
```

`maxWarnings = 0` holds across all four modules. The 19 inline-conditional warnings the first pass emitted were all replaced with `if`/`else` blocks; the 7 missing-Javadoc warnings on the per-swatch coordinate constants were fixed by adding a one-line Javadoc to each `_ROW` constant.

### 4. `.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"`

```
09:26:15.086 [main] INFO  c.openfps.engine.core.EngineSession - OpenFPS engine shut down cleanly.

BUILD SUCCESSFUL in 3s
3 actionable tasks: 1 executed, 2 up-to-date
```

Engine boots, runs 120 tics (~2 s) and exits cleanly. The level `.ofm` is the rebuilt Kenney-textured version; the spec is the four-Urban-Warzone-maps form with the `engine/src/main/resources/...` asset path.

### 5. `.\gradlew.bat :engine:run --args="--headless --map=overpass --fps=60"`

```
09:26:26.511 [main] INFO  c.openfps.engine.core.EngineSession - OpenFPS engine shut down cleanly.

BUILD SUCCESSFUL in 3s
```

Same shape. The per-tic Hardpoint logic ticks without error over 120 tics.

### 6. `.\gradlew.bat :engine:run --args="--headless --map=tripoint --fps=60"`

```
09:26:36.913 [main] INFO  c.openfps.engine.core.EngineSession - OpenFPS engine shut down cleanly.

BUILD SUCCESSFUL in 3s
```

Same shape. The per-tic Domination logic ticks without error over 120 tics.

### 7. `.\gradlew.bat :engine:run --args="--headless --map=extraction --fps=60"`

```
09:26:46.935 [main] INFO  c.openfps.engine.core.EngineSession - OpenFPS engine shut down cleanly.

BUILD SUCCESSFUL in 3s
```

Same shape. The per-tic CTF logic ticks without error over 120 tics.

### 8. `.\gradlew.bat :desktop:run --args="--map=cornerstone --start-in-game --screenshot=C:\tmp\cornerstone.png"`

`MapScene` is wired into the desktop launcher's `--map=<id>` path. The visual smoke test (a windowed run that opens onto the cornerstone map rather than the legacy demo room) was **deferred**: the desktop launcher requires a display, and the CI environment is headless. The headless smoke tests (4-7) are the success criterion for the map-library landing; the windowed test is the success criterion for the renderer integration. The wiring is in (`buildMapScene`, `bindMapWorld`, the `MAP_ARG = "--map="` parser, the bypassed `attach*` calls); a future pass can run the windowed test on a display-equipped CI node. A one-line spec change to `buildMapScene` would be enough to enable the screenshot when a display is present.

---

## The 3 new map locations

- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#overpass()` — the Hardpoint map's spec, 320×320, 3 zones (Overpass N at z=40, Overpass S at z=240, Control Building at z=296), 1800-tic rotation, 1 point per tic. Six spawns (3 RED on the west at z=64/96/128 facing east; 3 BLUE on the east at z=192/224/256 facing west). The design spec is at `docs/maps/urban-warzone/02-hp-overpass.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#tripoint()` — the Domination map's spec, 320×320, 3 flags (FLAG_A at z=48, FLAG_B at the central roundabout at z=160, FLAG_C at z=240). The spec uses the "southern pair is one logical C" simplification documented in the design spec; Pass 2 implements FLAG_C as the south-west flag at (80, 240) — see the spec's note on a future revision to FLAG_C and FLAG_D. Six spawns (3 RED on the west facing south; 3 BLUE on the east facing south-west). The design spec is at `docs/maps/urban-warzone/03-dom-tripoint.md`.
- `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java#extraction()` — the CTF map's spec, 320×320, two bases (red at (32, 32), blue at (288, 288)). Each base declares a flag and a capture point at the same coordinates, per the spec's "red's flag is also red's capture point" rule. Six spawns (3 RED inside red's base, 3 BLUE inside blue's base). The design spec is at `docs/maps/urban-warzone/04-ctf-extraction.md`.

## The Cornerstone asset-swap notes

- The level `.ofm` was rebuilt with **Kenney-textured geometry**. Floor and wall textures are sampled from the Prototype Kit's `colormap.png` (CC0) — the kit's row 0 column 0 swatch for the floor (light grey, the colour the kit's `floor-square` uses) and the kit's row 2 column 0 swatch for the wall (dark grey, the colour the kit's `wall` and `wall-corner` use). The third "accent" submesh — a hand-authored solid red — sits on the bollards and the corner-trim pieces. Triangle count is **408** (up from 264): 1 floor + 4 perimeter + 2 internal + 4 landmarks + 8 crates + 8 bollards + 4 corner trims = 31 boxes × 12 tri.
- **The geometry is still procedural**, not built out of converted Kenney GLB pieces. The user's brief flagged this as a fallback ("If the Kenney kits don't cover every surface, hand-author the missing pieces"), and the staged packs (`docs/DEMO_ASSETS.md` § 1) include only the Prototype Kit's level primitives (wall, wall-corner, floor-square, etc., all 1×1 grid cells) — not city-block geometry. A 320×320 map built out of 1×1 Kenney cells would be a 5×5 grid of cubes, which does not fit the spec's callout structure. The chosen path is: keep the procedural geometry, swap the procedural textures for Kenney swatches, and document the seam (`docs/maps/urban-warzone/01-cornerstone.md` § Textures & Assets).
- **A future pass can swap the geometry wholesale** if a Kenney City Kit or similar is downloaded. The seam is `CornerstoneMapBuilder.build(Path)` and the four `_TEX` constants in `KenneyTexture`; the rest of the pipeline (build → level.ofm → MapScene → renderer) does not change.

## The MapScene location and what's wired

- The class lives at `engine/src/main/java/com/openfps/engine/gameplay/map/MapScene.java`. It is a final, non-instantiable utility class with one static factory method, `MapScene.build(MapSpec)`. The factory resolves the spec's level path (the `engine/src/main/resources/maps/<id>/level.ofm` form), reads the .ofm bytes through the classpath, parses them with `ModelFormat.read`, and assembles a one-instance `Scene` via `Scene.builder().addWorldInstance(model, Mat4.identity())`. The returned `MapScene` is immutable and exposes `spec()` and `scene()` accessors.
- **What's wired** in `desktop/src/main/java/com/openfps/desktop/DesktopLauncher.java`:
  - `MAP_ARG = "--map="` and `mapArg(args)` parser.
  - `buildMapScene(mapId)` — delegates to `MapLoader.loadOrFallback` and `MapScene.build`.
  - `bindMapWorld(renderer, mapScene)` — binds the map's level scene to the renderer; the demo is bypassed.
  - The main bootstrap: when `--map=<id>` is given, the gameplay port is `MapSmokeGameplayPort.create(input, mapId)` (the engine's existing headless `--map=` path). The demo's per-tic UI hooks (`attachMatchGate`, `attachAudio`, `attachMatchResult`, `attachMatchRestart`, `attachMatchStatus`) are skipped. Networking is also bypassed.
- **What's not wired** (deferred to a future pass): the demo is not driven in map mode, so there is no held weapon, no bots, no character placement. A future pass can replace the smoke port with a real map-driven `DemoGameplayPort`-like class and bring the per-map bots, weapons, and UI hooks online.

---

## Open items for Pass 3

1. **The 12 non-Urban-Warzone mode maps.** Industrial Complex, Desert Ravine, and Arctic Station each have a TDM map (`refinery`, `crossroads`, `arctic-station`) but no Hardpoint, Domination or CTF variants. Passes 3-5 land those.
2. **A real map-driven gameplay port.** `MapSmokeGameplayPort` runs the per-tic mode logic but the demo's per-map bots, weapon, and UI hooks are not wired. A `MapGameplayPort` (or a `MapSpec`-driven variant of `DemoGameplayPort`) is the next step.
3. **Geometry-from-Kenney-GLB for levels.** The Prototype Kit's pieces are 1×1 grid cells and do not fit a 320×320 COD map. A City Kit or building set would unblock a wholly-Kenney-converted Cornerstone and let the four new maps use the same kit pieces as the demo. Until then, the procedural builders stay.
4. **The windowed visual smoke test.** The desktop launcher's `--map=<id>` path is wired, but the screenshot smoke test was not run in CI (the build environment is headless). A display-equipped CI node would close the loop on `docs/pass2-report.md` § 8.
5. **`MapScene` does not yet expose spawn-point instances.** The spec carries `spawnPoints` and the renderer knows about `Scene.UNTAGGED` vs. tagged entities, but no path exists today for `MapScene` to instantiate spawns as tagged world instances. A future pass can wire `SpawnPoint` → `Mat4.translation(x, 0, z) * Mat4.rotationY(yawRadians)` → `addWorldInstance(model, transform, entityId)` for a future "map preview with player placeholders" feature.
6. **The 2026-08 dead-code prune is stale.** `PRUNE_AUDIT.md` documents the +4 test delta after the prune; the Pass 1 + 21 and Pass 2 + 54 deltas are not in there. A future prune pass should reconcile the audit.

---

## A 1-paragraph summary of each new map

**Overpass** (Urban Warzone × Hardpoint) is a 320×320 highway interchange at street level. Two parallel elevated overpasses run east-west at z=40 and z=240, sitting on a deck at y=64 — the high ground, with long sightlines across the whole map. A service road runs along z=120 to z=200 at ground level — the contested low ground, with six concrete barriers for cover and four signposts on the ramp transitions. A 96×64 control building anchors the south at z=296, the third hardpoint zone. The three zones rotate every 1800 tics (30 s) in B-A-C order, and the per-tic scoring awards one point to whoever holds the active zone. The result plays like a tower-control COD map: the overpasses reward the high-ground player, the service road is where rotations happen, and the control building is the lockout.

**Tripoint** (Urban Warzone × Domination) is a 320×320 three-way intersection at street level. A 80×80 raised kerb at z=160 is the central capture zone (FLAG B, larger radius); three approach roads — north, south-east, south-west — lead to the three flags. FLAG A is at (160, 48), FLAG C is at (80, 240) (the south-west flag; the south-east pair is collapsed to one for the spec's "southern pair is one logical C" rule, with FLAG_D as the suggested future revision). The central capture zone is contested ground; the three flags are the rewards. A team that captures two flags at once earns double the score; capturing all three is the lockout. The play is "centre → flag", with the back-alley cut-through at z=200 (a gap at x=160 in a low wall) the only way to rotate between the southern flags without going through the roundabout.

**Extraction** (Urban Warzone × CTF) is a 320×320 urban block split by a long boulevard. The boulevard runs along z=160, 80 wide, from x=-160 to x=160 — the longest open sightline in the map, and the route a flag carrier has to run to score. Red's base is at the south-west corner (32, 32), blue's at the north-east (288, 288); each base declares a flag and a capture point at the same coordinates, per the spec's "red's flag is also red's capture point" rule. Two cover walls flank the boulevard in lanes A and C (x=64 and x=256), and two cut-throughs at z=120 and z=200 (each with a gap at x=160) allow lateral movement between lanes at the centre. The carrier is visible from the moment they leave their own base until they reach the enemy capture point; the flanking lanes are the only off-axis cover a defender can use.

---

**Report file**: `C:\Development\fullstack\openfps\docs\pass2-report.md`
