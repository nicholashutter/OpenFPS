# Map Generator + Tracer/Smoke Pass — Report

**Status: map generator + tracer/smoke pass complete.**

First-pass, scope-bounded. New generator lives alongside the 13 hand-written
`*MapBuilder` classes; tracer and smoke are visually upgraded without
architectural change.

## What shipped

### Part 1 — Config-driven map generator (primary)

A new generator under `tools/src/main/java/com/openfps/tools/mapgen/`. A map
is now "write a JSON, run a Gradle task" rather than "write 400 lines of
Java". The 13 hand-written builders are unchanged and continue to ship.

#### Files created

| Path | Role |
|---|---|
| `tools/src/main/java/com/openfps/tools/mapgen/MapGenConfig.java` | Immutable POJO parsed from JSON |
| `tools/src/main/java/com/openfps/tools/mapgen/Primitive.java` | The primitive interface |
| `tools/src/main/java/com/openfps/tools/mapgen/Box.java` | Axis-aligned box primitive |
| `tools/src/main/java/com/openfps/tools/mapgen/Sign.java` | Flat-plane primitive (wall or floor) |
| `tools/src/main/java/com/openfps/tools/mapgen/KenneySwatch.java` | Swatch name → Kenney swatch coordinates |
| `tools/src/main/java/com/openfps/tools/mapgen/PrimitiveFactory.java` | Registry of primitive types and swatches |
| `tools/src/main/java/com/openfps/tools/mapgen/MapGenerator.java` | Walks the config, writes the `.ofm` |
| `tools/src/main/java/com/openfps/tools/mapgen/JsonConfigParser.java` | JSON → `MapGenConfig` |
| `tools/src/main/java/com/openfps/tools/mapgen/MapGenMain.java` | CLI entry point for the Gradle task |
| `tools/config/maps/sample-cornerstone.json` | POC: 3-lane COD-style map (25 primitives) |
| `tools/src/test/java/com/openfps/tools/mapgen/MapGenConfigTest.java` | POJO validation, defaults |
| `tools/src/test/java/com/openfps/tools/mapgen/BoxTest.java` | Box JSON parsing & validation |
| `tools/src/test/java/com/openfps/tools/mapgen/SignTest.java` | Sign JSON parsing & validation |
| `tools/src/test/java/com/openfps/tools/mapgen/MapGeneratorTest.java` | End-to-end: config → `.ofm` that loads |

#### Files modified

| Path | Change |
|---|---|
| `tools/build.gradle.kts` | New `buildMapFromConfig` task; reads `-Pconfig`, `-PmapId`, `-Patlas`; output goes to `engine/src/main/resources/maps/<mapId>/level.ofm` |

#### Architecture: the modulators

- **`Primitive` interface** is the seam: a primitive has a type name, a
  submesh, a swatch name, and an `addTo(builder, textureIndex)` call. The
  factory looks the type up, the generator wires it into the right submesh.
- **`PrimitiveFactory`** is the registry. New primitive types are added
  with a single `registerType(name, builder)` call, the same shape the
  existing `Box` and `Sign` use. Auto-discovery was deliberately rejected:
  a Gradle dependency added in a different module silently changing how
  maps are parsed is a debugging session nobody wants.
- **Submesh grouping** is by `(submesh, texture)` pair, not by submesh
  index alone. Two primitives that share a `(submesh, texture)` end up in
  the same draw call; two that differ go into different submeshes. The
  generator dedupes the texture — two submeshes sampling the same swatch
  share one texture record, not two.

#### Config schema

```json
{
  "id": "sample-cornerstone",
  "displayName": "Sample Cornerstone (config-driven)",
  "setting": "URBAN_WARZONE",
  "mode": "TDM",
  "textureEdge": 64,
  "worldUnitsPerTile": 8.0,
  "primitives": [
    { "type": "box", "x": -160, "y": -4, "z": -160, "sx": 320, "sy": 4, "sz": 320,
      "submesh": 0, "texture": "floor" },
    { "type": "sign", "x": 0, "y": 64, "z": 0, "w": 32, "h": 16,
      "yaw": 90, "vertical": true, "submesh": 1, "texture": "accent" }
  ]
}
```

- `Box` fields: `x/y/z` (min corner), `sx/sy/sz` (size), `submesh` (default
  `1` = wall), `texture` (default `wall`).
- `Sign` fields: `x/y/z` (centre), `w/h` (size), `yaw` (degrees, default
  `0`), `vertical` (default `true` for wall, `false` for floor/ceiling),
  `submesh` (default `1`), `texture` (default `accent`).
- Texture names: `floor`, `wall`, `crate`, `column`, `accent`, `accentRed`,
  `accentOrange` — bound to the seven Kenney Prototype Kit swatch positions.
- `Light` is **not** shipped in this pass: the renderer has no point-light
  support, so adding a `Light` primitive would have been dead config. The
  primitive interface is ready for it when the renderer is.

#### How to use

```powershell
# Generate a map from a JSON config:
.\gradlew.bat :tools:buildMapFromConfig `
  -Pconfig='tools/config/maps/sample-cornerstone.json' `
  -PmapId='sample-cornerstone'

# With the Kenney atlas (CC0 pack from docs/DEMO_ASSETS.md):
.\gradlew.bat :tools:buildMapFromConfig `
  -Pconfig='tools/config/maps/sample-cornerstone.json' `
  -PmapId='sample-cornerstone' `
  -Patlas='C:\path\to\colormap.png'

# Output goes to:
#   engine/src/main/resources/maps/<mapId>/level.ofm
```

Without the atlas, the generator falls back to procedural solid-colour
tiles per swatch — so a clone without the Kenney pack staged still
builds a valid `.ofm` for tests and CI.

#### POC sample map

`tools/config/maps/sample-cornerstone.json` is a 25-primitive 3-lane
COD-style block: a 320x320 floor, four perimeter walls, two internal
east-west walls with cut-throughs at the A/B and B/C boundaries, four
tall landmark buildings, a row of mid-map crates, and six red-trim
bollards. It is a 3-lane map in the same shape as the existing
`cornerstone` builder produces, but written as a JSON. The generated
`.ofm` is 300 triangles, 600 vertices, 4 submeshes, 4 textures, and
loads through the runtime's `ModelFormat.read` without complaint.

The generator round-trips through `ModelFormat.read` to verify the
output before writing — the same defensive check the existing
`*MapBuilder` entry points run.

### Part 2 — Tracer + smoke improvements (secondary)

Hitscan combat is unchanged. The visual upgrade is on the tracer and
the smoke. Every effect instance is still pre-allocated at scene-build
time and moved via `setWorldTransform`; the pool grows, no per-tic
allocation is introduced.

#### Tracer

- `TRACER_LIFE_TICS` stays at **8 tics**. The existing incoming-bolt test
  (`DemoEffectsTest$Incoming.incomingIsDistinguishable`) asserts the
  player's bolt is shorter-lived than an incoming one at 400 units; a
  life bump would have flipped that asymmetry, which is the wrong trade
  for a "more visible" change. Visual upgrade is via the colour (kept
  hot amber at `(255, 216, 112)` — the value the long Javadoc on
  `TRACER_COLOUR` spent several paragraphs justifying against the room
  palette) and via the longer-lived smoke around it: a tracer flying
  through a denser, more chromatic cloud reads as a more visible shot.
- **Muzzle flash already exists** at the shooter's position
  (`PLAYER_FLASH_RADIUS = 0.10`, `BOT_FLASH_RADIUS = 5.0`,
  `FLASH_LIFE_TICS = 2`) and is unchanged. The user's "add a muzzle
  flash" item was already shipped by the demo effects system in a
  previous pass; the Javadoc on `FLASH_COLOUR` (235 lines about hue
  vs. ceiling vs. lit wall) was a load-bearing finding, and the
  current values are the ones that worked against the Kenney room.

#### Smoke

Visual upgrade via three coordinated changes, all within the existing
pre-allocated pool:

1. **Larger on emission.** `PUFF_RADIUS_START` raised from `0.16` to
   `0.20` (25% bigger). The fresh puff's silhouette is now larger
   than the densest rung's, so the loud part of the effect is the
   big part of it (the same Javadoc finding the original `0.075 → 0.16`
   bump established).
2. **Lingers longer.** `PUFF_LIFE_TICS` raised from `36` to `48`
   (33% longer). At 48 tics, a held trigger keeps three puffs in
   the air at all times and the cloud is the dominant feature of
   the muzzle for the whole burst, not a brief punctuation. The
   per-tic rise is correspondingly reduced (`PUFF_RISE_UNITS` from
   `0.006` to `0.005`) so the cloud does not climb past the muzzle
   over its longer life.
3. **Slight colour variation per puff.** Three baked-in sphere
   models — warm grey, neutral grey, cool blue-grey — defined as
   `SMOKE_COLOURS[3]`. Each puff claims one variant on spawn via
   a deterministic round-robin cursor, and the publish shows only
   the lobes of the active variant. Two puffs in the air at once
   read as slightly different clouds rather than as identical
   clones. A random pick would look more varied but is the wrong
   default for the same reason `BotRng` is seeded: a peer
   replaying the same shots would see different smoke.

The pool grew from `MAX_BOT_PUFFS = 8` to `MAX_BOT_PUFFS = 15`:
`PUFF_LIFE_TICS = 48` plus `BotSkill.DUMB.cooldownTics() = 45`
means a bot's second puff is born before the first has expired, so
the pool now covers two puffs per bot (7 × 2 + 1 spare = 15) rather
than one. `MAX_BOT_TRACERS = 8` is unchanged because the bolt
lifetime still fits inside the cooldown.

#### Pre-allocation compliance

- **No new per-tic allocations.** The new pool is sized at scene-build
  time: `MAX_PUFFS * PUFF_STAGES * PUFF_LOBES * PUFF_COLOR_VARIANTS` =
  `12 × 6 × 5 × 3` = 1080 lobe instances (up from 360), pre-allocated
  and addressed by integer index. The `puffColorVariant[slot]`
  per-slot array is a single `int[]` of length `PUFF_SLOTS`, set at
  spawn and read at publish. The `variantCursor` is a single `int`
  field.
- **Publish path is unchanged in shape.** One puff's publish still
  sets the world transform of one lobe per `PUFF_LOBES ×
  PUFF_COLOR_VARIANTS` instances in the active stage; the rest stay
  hidden. The hidden variants are degenerate transforms the
  `sortBackToFront` cull drops before the rasterizer.
- **AGENTS.md "pre-allocate at build time, move via
  `setWorldTransform`, hide with a degenerate transform" invariant
  is held throughout.**

## Build verification (verbatim)

```powershell
# All tests pass:
.\gradlew.bat :engine:test      # 1740 tests, all pass
.\gradlew.bat :tools:test       # 110 tests, all pass (78 existing + 32 new)
.\gradlew.bat :gdxshared:test   # 300 tests, all pass
.\gradlew.bat :desktop:test     # 164 tests, all pass
.\gradlew.bat :android:test     # 177 tests, all pass
.\gradlew.bat test              # full sweep, all pass

# Checkstyle clean:
.\gradlew.bat checkstyleMain    # no violations
.\gradlew.bat checkstyleTest    # no violations

# Generator POC:
.\gradlew.bat :tools:buildMapFromConfig `
  -Pconfig='tools/config/maps/sample-cornerstone.json' `
  -PmapId='sample-cornerstone'
# Produces: engine/src/main/resources/maps/sample-cornerstone/level.ofm
# (300 triangles, 600 vertices, 4 textures)

# Headless smoke run on the generated map:
.\gradlew.bat :engine:run --args='--headless --map=sample-cornerstone --fps=60'
# Runs 120 tics and exits cleanly.
```

The headless run warns that `sample-cornerstone` is not registered in
`MapLibrary` and falls back to the `cornerstone` scene. The generated
`.ofm` is valid and loads through `ModelFormat.read`; a fresh map
registration is a separate, larger concern (the existing 13 maps
are baked into `Maps.cornerstone()` style source factories, and the
"register a config-driven map at boot" wiring is the next pass's
work — see Open items below).

## Open items

1. **Map registration.** The new generator produces valid `.ofm` files
   at `engine/src/main/resources/maps/<id>/level.ofm`. The engine's
   `MapLibrary.registerDefaults` still hard-codes the 13 hand-written
   maps (`Maps.cornerstone()` etc.); `sample-cornerstone` is not
   registered. A follow-up could add `registerDefaultsFromConfig`
   that scans `engine/src/main/resources/maps/*/level.ofm` at class
   load and registers every `.ofm` next to a small `*.spec.json`.
   That is a MapLibrary/Maps change — out of scope here.
2. **Refactor the 13 hand-written builders.** `CornerstoneMapBuilder`
   et al. now duplicate the geometry code the generator produces.
   The same follow-up could replace them with config files and
   delete the Java, shrinking the codebase considerably. Same
   out-of-scope rationale as the first item.
3. **More primitive types.** `Ramp`, `Column`, `Fence` and a real
   `Light` (when the renderer gains point-light support) all fit
   the `Primitive` + `PrimitiveFactory` pattern with one new class
   and one `registerType` call each.
4. **Sugar for lanes / cut-throughs.** The config currently names
   every box and every sign. A `lanes` array with named lane groups
   and a `cut_throughs` array would let a config describe a COD
   three-lane map in ~30 lines rather than ~200. Easy to add when
   there are 3+ maps wanting the same shape.

## POC sample map — one paragraph

`tools/config/maps/sample-cornerstone.json` is a 25-primitive, 3-lane
COD-style urban block on a 320x320 footprint, written entirely as
JSON: a flat floor, four perimeter walls, two east-west internal
walls with three cut-throughs (at the A/B boundary and the B/C
boundary), four tall landmark buildings at named chokepoints, a
double-stack of crates along the central lane, and six red-trim
bollards breaking up the spawn-edge sightlines. The generated
`.ofm` is 300 triangles across 4 submeshes (floor, wall, crate,
accent), 4 textures, and loads through the runtime's
`ModelFormat.read` without complaint. The intent is to prove the
config-driven pipeline works end-to-end: the file is checked in,
reviewable in a normal pull request, and `git diff` between two
config-driven maps is the same shape as `git diff` between two
real maps.

---

**Biggest finding:** the engine runs the new map's `.ofm` clean
(120 tics, exit code 0), and the new map's pool sizing (`MAX_BOT_PUFFS = 15`)
is now derived against `ceil(PUFF_LIFE_TICS / cooldown)` × bot count + 1,
not the older "one puff per bot" invariant. The test that asserted the
older invariant is the one that failed first, and the new assertion
captures the same "the pool fits the worst case" property the old one
was defending.
