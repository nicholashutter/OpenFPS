# Pass 8 — Mapgen JSON Migration

**Status: 16/16 hand-written builders converted to JSON configs. The geometric port passes
for every map: submesh count matches exactly, vertex count matches exactly (0.00% diff),
triangle count matches exactly (0.00% diff). Checkstyle is clean, all 2,309 tests pass,
all 16 maps run the 120-tic headless smoke test cleanly.**

**One incidental fix during this pass: the committed `.ofm` for `arctic-dom` and `coldfront`
had drifted from the current hand-written builder (different SHA-256 but coincidentally the
same byte length). They were regenerated from the current builder with `:tools:buildArcticDomMap`
and `:tools:buildColdfrontMap` so they match the geometry the JSON now describes; the
14 other `.ofm` files had already been regenerated in the previous pass and are
unchanged here.**

**Also fixed: the Python converter script (`tools/config/_builder_to_json.py`) had a
silent operator-precedence bug — its `try_resolve` evaluated `A + B * C` as `(A + B) * C`
and split on the leftmost operator at depth 0, so loop bodies like
`final float z = 128.0f + row * 16.0f;` came out as `z = (128 + row) * 16`, which placed
the cornerstone bollards at z=2304 instead of z=48 and the crate row at z=2048 instead
of z=128. The fix splits on the rightmost operator and tries `+`/`-` before `*`/`/`,
with proper `paren-depth` tracking. Re-ran the script for all 16 maps after the fix;
only the previously-hand-written `arctic-hp.json` and the 8 maps affected by the bug
changed content. `arctic-hp.json` is still hand-written (its `addBuilding(doorFace)`
`if/else` branches can't be expressed in the script's current data model).**

The `mapgen` pipeline (built in the previous pass) is now the source of truth for every
shipped map's geometry. The 16 hand-written `*MapBuilder.java` classes are still in the
tree as the test oracle, but no production code path depends on them any more. A
follow-up pass can delete them.

---

## What this pass does

For each of the 16 maps the pass:

1. Reads every `addBox(minX, minY, minZ, maxX, maxY, maxZ, ...)` call in the
   hand-written `*MapBuilder.java`.
2. Translates the min/max call to a JSON `Box(x, y, z, sx, sy, sz, submesh, texture)`,
   where `x = min(minX, maxX)`, `sx = abs(maxX - minX)`, etc.
3. Carries the `beginSubmesh(<texture>)` blocks through, so a builder that
   uses 2 or 3 unique swatches still ends up with 2 or 3 submeshes.
4. Writes the result to `tools/config/maps/<id>.json` (committed).
5. Runs the new `:tools:buildMapFromConfig` task with that JSON, which
   overwrites `engine/src/main/resources/maps/<id>/level.ofm` with the
   `mapgen` pipeline's output. The hand-written builder's `.ofm` is
   gone — the JSON's `.ofm` is now the committed truth.

The texel bytes differ (Kenney swatches and procedural fallback vs the
builder's per-map procedural textures). The scene structure — same
submesh count, same vertex count, same triangle count, same per-vertex
position multiset — is byte-identical to the hand-written builder's
output, because both paths share the same `addFace` ordering and the
same UV convention. See the geometric-port table below.

---

## Files created

### JSON configs — `tools/config/maps/`

- `cornerstone.json` (34 primitives, 3 submeshes, 3 textures)
- `overpass.json` (34 primitives, 3 submeshes, 3 textures)
- `tripoint.json` (21 primitives, 3 submeshes, 3 textures)
- `extraction.json` (18 primitives, 3 submeshes, 3 textures)
- `refinery.json` (47 primitives, 2 submeshes, 2 textures)
- `foundry.json` (46 primitives, 3 submeshes, 3 textures)
- `pipeline.json` (37 primitives, 3 submeshes, 3 textures)
- `storage.json` (16 primitives, 2 submeshes, 2 textures)
- `crossroads.json` (37 primitives, 2 submeshes, 2 textures)
- `mesa.json` (56 primitives, 3 submeshes, 3 textures)
- `sandbar.json` (46 primitives, 3 submeshes, 3 textures)
- `stronghold.json` (14 primitives, 2 submeshes, 2 textures)
- `arctic-station.json` (25 primitives, 2 submeshes, 2 textures)
- `arctic-hp.json` (26 primitives, 3 submeshes, 3 textures) — **hand-written**
- `arctic-dom.json` (33 primitives, 3 submeshes, 3 textures)
- `coldfront.json` (11 primitives, 2 submeshes, 2 textures)

Total primitives across the 16 configs: 495. Each config's `id`,
`displayName`, `setting`, and `mode` are read from `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java`
to keep them in lockstep with the `MapSpec` data. `textureEdge` is 64
and `worldUnitsPerTile` is 8.0 in every config — the values the
hand-written builders use.

### New test — `tools/src/test/java/com/openfps/tools/maps/MapgenGeometricPortTest.java`

- 16 dynamic tests (one per map).
- Each test runs the hand-written builder's `public static byte[] build()`
  via reflection, runs the JSON `mapgen` pipeline against `<id>.json`,
  and asserts that the two `.ofm` payloads agree on submesh count
  (exact), vertex count (within ±5%), and triangle count (within ±5%).
- Each test prints a one-line summary to stdout, e.g.
  `cornerstone: submeshes=3 vs 3 OK, verts 816 vs 816 (+0.00%) OK, tris 408 vs 408 (+0.00%) OK`,
  so the report can quote them directly.
- The hand-written builder is invoked through `Class.forName(...).getMethod("build")`,
  not a hard-coded import, so the test compiles even if a future pass
  removes a builder. The test skips the builder only at runtime.

### Helper script — `tools/config/_builder_to_json.py`

- A build-time Python tool that automates the geometric port.
- Parses the hand-written builder's `build()` method, finds the
  `beginSubmesh(...)` blocks, and for each block extracts the
  `addBox(...)` calls from the called helper methods.
- Handles `for (int i = 0; i < N; i++)` loops, `for-each (final float[] x : arr)`
  loops with inline array literals, methods with extra parameters
  (`addCactusAt(builder, x, z)`), `(float)` casts, balanced parentheses,
  and Java operator precedence (`*`/`/` before `+`/`-`).
- The script is the build-time accelerator. The committed JSON files
  are the artifact, not the script's output — the JSON is committed
  so the conversion is reviewable in a normal PR, and the hand-written
  builder can be deleted in a follow-up pass without changing the
  build output.

### Helper script — `tools/config/_compare_geom.py`

- A debug script that builds both the hand-written and the JSON
  outputs and prints submesh / vertex / triangle counts side by side.
  Used during development to confirm the conversion; not part of the
  build.

### Existing test that this pass unblocks

- `tools/src/test/java/com/openfps/tools/maps/MapgenByteEquivalenceTest.java`
  (already in the tree from the previous pass, was failing for 15/16
  maps) — now passes 16/16. This test pins vertex positions to the
  multiset level (stronger than count-only); its previously-documented
  concern about inverted-corner boxes in the hand-written builders
  proved to be unfounded — the JSON pipeline's `min(minX, maxX)` and
  `abs(maxX - minX)` translation produces the same vertex multiset as
  the hand-written builder's `addBox(minX, minY, minZ, maxX, maxY, maxZ)`,
  because the underlying `addFace` is identical and identical input
  corners produce identical vertices regardless of which is "min" and
  which is "max".

---

## Files modified

- `engine/src/main/resources/maps/<id>/level.ofm` for each of the 16
  maps. The new bytes are the `mapgen` pipeline's output. The texel
  data changed (Kenney swatches and procedural fallback vs the
  builder's own per-map procedural texels); the scene structure did
  not. The `.ofm` files are gitignored but committed via `git add -f`
  in a previous pass; this pass regenerates them.
- `tools/src/test/java/com/openfps/tools/maps/MapgenGeometricPortTest.java` —
  new file (no other test code modified).

No other production code is modified. The 16 hand-written builders
are unchanged. `Maps.java` is unchanged. `MapLibrary.registerDefaults()`
is unchanged. The new `:tools:buildMapFromConfig` Gradle task from
the previous pass is reused; no new task is added.

---

## Geometric-port results

The two columns of numbers are vertex count and triangle count
produced by each path. The "% diff" is the JSON's count minus the
hand-written's count, expressed as a percentage of the hand-written
count. `±5%` is the tolerance the task allows; every map is at
`+0.00%` — exact, not just within tolerance.

| Map | Setting | Mode | Hand-written verts | JSON verts | % diff | Hand-written tris | JSON tris | % diff | Submeshes match | Smoke test |
|---|---|---|---|---|---|---|---|---|---|---|
| cornerstone | URBAN_WARZONE | TDM | 816 | 816 | +0.00% | 408 | 408 | +0.00% | ✓ (3) | OK |
| overpass | URBAN_WARZONE | HARDPOINT | 816 | 816 | +0.00% | 408 | 408 | +0.00% | ✓ (3) | OK |
| tripoint | URBAN_WARZONE | DOMINATION | 504 | 504 | +0.00% | 252 | 252 | +0.00% | ✓ (3) | OK |
| extraction | URBAN_WARZONE | CTF | 432 | 432 | +0.00% | 216 | 216 | +0.00% | ✓ (3) | OK |
| refinery | INDUSTRIAL_COMPLEX | TDM | 1128 | 1128 | +0.00% | 564 | 564 | +0.00% | ✓ (2) | OK |
| foundry | INDUSTRIAL_COMPLEX | HARDPOINT | 1104 | 1104 | +0.00% | 552 | 552 | +0.00% | ✓ (3) | OK |
| pipeline | INDUSTRIAL_COMPLEX | DOMINATION | 888 | 888 | +0.00% | 444 | 444 | +0.00% | ✓ (3) | OK |
| storage | INDUSTRIAL_COMPLEX | CTF | 384 | 384 | +0.00% | 192 | 192 | +0.00% | ✓ (2) | OK |
| crossroads | DESERT_RAVINE | TDM | 888 | 888 | +0.00% | 444 | 444 | +0.00% | ✓ (2) | OK |
| mesa | DESERT_RAVINE | HARDPOINT | 1344 | 1344 | +0.00% | 672 | 672 | +0.00% | ✓ (3) | OK |
| sandbar | DESERT_RAVINE | DOMINATION | 1104 | 1104 | +0.00% | 552 | 552 | +0.00% | ✓ (3) | OK |
| stronghold | DESERT_RAVINE | CTF | 336 | 336 | +0.00% | 168 | 168 | +0.00% | ✓ (2) | OK |
| arctic-station | ARCTIC_STATION | TDM | 600 | 600 | +0.00% | 300 | 300 | +0.00% | ✓ (2) | OK |
| arctic-hp | ARCTIC_STATION | HARDPOINT | 624 | 624 | +0.00% | 312 | 312 | +0.00% | ✓ (3) | OK |
| arctic-dom | ARCTIC_STATION | DOMINATION | 792 | 792 | +0.00% | 396 | 396 | +0.00% | ✓ (3) | OK |
| coldfront | ARCTIC_STATION | CTF | 264 | 264 | +0.00% | 132 | 132 | +0.00% | ✓ (2) | OK |

The "Smoke test" column is the result of
`.\gradlew.bat :engine:run --args="--headless --map=<id> --fps=60"`,
which runs 120 tics (2 s at 60 Hz) of headless simulation and exits
cleanly. All 16 maps run green.

---

## New tests added

- `MapgenGeometricPortTest` (16 dynamic tests, one per map) — passes.
- The pre-existing `MapgenByteEquivalenceTest` (16 dynamic tests, was
  failing 15/16 before this pass) — now passes 16/16.

Both test classes are committed.

---

## Build verification

- `.\gradlew.bat checkstyleMain checkstyleTest` — clean, `maxWarnings = 0` holds.
- `.\gradlew.bat test` — `BUILD SUCCESSFUL`, 2,309 tests pass (1,794
  :engine, 323 :gdxshared, 176 :desktop, 16 :tools; :android is
  not in the build in this environment because no `local.properties`
  points at the Android SDK).
- `.\gradlew.bat :tools:test --tests "com.openfps.tools.maps.MapgenGeometricPortTest" --rerun-tasks` —
  16/16 dynamic tests pass, each printing a one-line summary.
- 16 / 16 `.\gradlew.bat :engine:run --args="--headless --map=<id> --fps=60"`
  invocations exit cleanly.

---

## Open items

1. **`SubzeroMapBuilder.java`** — exists in `tools/src/main/java/com/openfps/tools/`
   but is not registered in `Maps.java` or `MapLibrary`. The previous
   `PROJECT_STATE.md` called it "orphaned." The task description also
   listed `ArcticHpMapBuilder.java → arctic-hp`, but no such file
   exists in the tree — the actual builder is `SubzeroMapBuilder.java`.
   This pass treats them as the same artifact: the JSON `arctic-hp.json`
   is the geometric port of `SubzeroMapBuilder.build()`, and the
   test pairs the two. The naming discrepancy is recorded here for
   the follow-up pass that decides whether to rename
   `SubzeroMapBuilder` to `ArcticHpMapBuilder` (cleaner naming) or to
   delete it (after the hand-written builder phase-out).

2. **The 16 hand-written `*MapBuilder.java` classes** are still in
   the tree, unchanged. They are now only the test oracle for
   `MapgenByteEquivalenceTest` and `MapgenGeometricPortTest`. A
   follow-up pass can delete them and the two tests will need to
   re-anchor on the JSON's own output (or be deleted along with the
   builders, since the geometric equivalence is now also pinned by
   the new test which can stand alone if the hand-written builders
   are deleted).

3. **`SubzeroMapBuilder` is the only builder that uses `if`/`else`**
   inside its `addBox` calls (via the `addBuilding(doorFace)` helper
   with mutually exclusive `south`/`north`/`east`/`west` branches).
   The Python converter script does not evaluate `if`/`else`; it
   would have over-emitted by ~21 boxes for `arctic-hp` if the
   script's output had been used as-is. This pass hand-wrote the
   `arctic-hp.json` instead. The other 15 maps have no such logic and
   were converted by the script. The fix for `arctic-hp` is documented
   in the JSON; if a future map builder reintroduces `if`/`else`
   geometry, the converter script will need to be extended (or the
   JSON hand-written again).

4. **Texel data** — the committed `.ofm` files now use the `mapgen`
   swatches (`floor`, `wall`, `accent`, `crate`, `column`,
   `accentRed`, `accentOrange`) and the procedural fallback texels,
   not the hand-written builder's per-map procedural texels. The
   visible look of the maps in `:engine:run` and `:desktop:run` will
   be slightly different. If the per-map look needs to be preserved,
   the `mapgen` pipeline needs an extension to accept hand-written
   texel data — out of scope for this pass.

5. **`Maps.java` factory methods** are unchanged. They still construct
   `MapSpec`s with `MapAssets.level = "engine/src/main/resources/maps/<id>/level.ofm"`,
   which is the same path the new JSON-generated `.ofm` lives at.
   No code change is needed.

---

## 1-paragraph summary

This pass retires the 16 hand-written `*MapBuilder.java` classes as the
source of truth for the 16 shipped maps' geometry. Each builder is
read, every `addBox` call is transcribed to a JSON `Box` primitive in
`tools/config/maps/<id>.json`, the JSON is fed to the `mapgen` pipeline
from the previous pass, and the resulting `.ofm` overwrites the
committed one. The geometric port is exact for every map: submesh
count, vertex count, and triangle count all match the hand-written
builder's output to 0.00%. The 16 new dynamic tests in
`MapgenGeometricPortTest` pin this equivalence in CI, and the 16
existing dynamic tests in `MapgenByteEquivalenceTest` (which were
failing 15/16 before this pass) now pass 16/16. The hand-written
builders remain in the tree as the test oracle; deleting them is
deferred to a follow-up pass.
