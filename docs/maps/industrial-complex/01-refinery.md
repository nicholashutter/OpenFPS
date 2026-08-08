# refinery — Refinery

**Setting**: Industrial Complex
**Mode**: TDM
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A mid-sized chemical refinery: three tall distillation columns at the north end, a large open process hall running across the middle with internal pipework at mid-height, and three wide boiler structures anchoring the south. The map plays in three vertical layers — floor (y=0), mid-level catwalks (y=64) reached by four corner stairways, and tank tops (y=128) reached by climbing onto the columns. Catwalks cross the map at mid-height so a player who has climbed once can rotate between lanes without coming back to the floor. The play is vertical as much as horizontal, and the chokepoints are the four cut-throughs in the process hall walls plus the four stairways.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |     [Tank A1]   [Tank A2]   [Tank A3]
         |     (-100,40)   (0,40)      (100,40)
         |     ┌──┐        ┌──┐         ┌──┐
         |     │  │        │  │         │  │       y=128 tops
         |     │  │        │  │         │  │
         |     └──┘        └──┘         └──┘
 LANE A  |  ─────  catwalk at y=64, runs N-S at x=0  ─────
  (N)    |  ─────  tanks are TALL landmarks, not cover ─────
         |
         |  ╔════════════════════════════╗  process hall
         |  ║   cut-through (x=0)        ║  walls
         |  ║                             ║
         |  ║                             ║
 LANE B  |  ║  ┌────────┐   cut-through  ║
  (M)    |  ║  │control │   (x=130)      ║
         |  ║  │ room   │                ║     z=110 → 210
         |  ║  │ 0,160  │   ┌─────────────╨──┐
         |  ║  └────────┘   │ stairs        │
         |  ║               │ (4 corners)  │
         |  ║               └──────────────┘
         |  ╚════════════════════════════╝
         |  ─────  catwalk at y=64 runs E-W through hall  ─────
         |  ─────  pipes hang below the catwalk (y=56-62)  ─────
         |
         |  ┌──────┐  ┌──────┐  ┌──────┐
         |  │B_C_W │  │B_C_C │  │B_C_E │      (boiler row)
 LANE C  |  │-100 │  │  0   │  │ 100  │
  (S)    |  │270  │  │ 270  │  │ 270  │
         |  └──────┘  └──────┘  └──────┘
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

- **Lane A (North)** — the tank row. Three tall distillation columns at (-100, 40), (0, 40), and (100, 40) provide the high ground. The columns are visible from across the map and a player on top has a 360° sightline over the process hall. A short N-S catwalk at y=64 connects the tanks to the process hall; without that catwalk, a player on a tank top would have to drop to the floor to cross the map.
- **Lane B (Middle)** — the process hall. A 260×100 building with walls on all four sides up to y=64, open at the top. Internal pipework hangs just under the catwalk (y=56-62, eight segments). A small enclosed Control Room at the centre (x=0, z=144-176) is the only solid cover in the hall that reaches above waist height. The hall has two cut-throughs: one in the north wall at x=0, one in the east wall at z=160. The two cut-throughs are the only ways in and out of the hall at floor level.
- **Lane C (South)** — the boiler row. Three wide low boilers at (-100, 270), (0, 270), and (100, 270). Each is 40 wide × 40 tall × 40 deep, so the boilers are waist-height cover, not head-height cover. The two gaps between the boilers (at x=-50 and x=+50) are the only ways through the boiler row at floor level.

## Cut-throughs

- **A → B** (north wall of process hall, at x=0): one wide cut-through, 16 units across. A player running south from the tank row drops into the process hall through this gap.
- **B → C** (south wall of process hall, at x=0): one wide cut-through, 16 units across. The mirror of the A→B cut-through; a player running south from the process hall reaches the boiler row through it.
- **B east** (east wall of process hall, at z=160): one cut-through, 16 units across. The lateral cut-through — a player crossing the map inside the hall exits through this gap.
- **B west** (west wall of process hall, at z=160): the mirror of the east cut-through.
- **B stairways**: four corner stairways at (-95, 0, 105), (95, 0, 105), (-95, 0, 205), (95, 0, 205). Each is 10×64×10, climbing the wall from floor to the catwalk ring at y=64.
- **C gaps** (between boilers, at x=-50 and x=+50): two narrow gaps, 40 units across. The boilers are far enough apart to walk through; closer to a doorway than to a chokepoint.

## Callouts

- `Distillation Tower` — the west tank, at (-100, 40). The leftmost high-ground position.
- `Tank Row` — the centre tank at (0, 40). The mid-tank with the most cross-map sightlines.
- `Tank Row East` — the east tank at (100, 40).
- `Process Hall West` — the west end of the hall, near the SW stairway. The defensive spawn-side callout for RED.
- `Control Room` — the small enclosed room at (0, 160). The contested ground in the centre; the only solid cover in the hall that reaches above waist height.
- `Process Hall East` — the east end of the hall, near the NE stairway. The defensive spawn-side callout for BLUE.
- `Boiler West` — the west boiler at (-100, 270). The defensive cover for RED on lane C.
- `Boiler Centre` — the centre boiler at (0, 270).
- `Boiler East` — the east boiler at (100, 270).
- `Stairs (SW)`, `Stairs (SE)`, `Stairs (NW)`, `Stairs (NE)` — the four corner stairways.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 60), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 260) on the west edge. Facings aimed at the process hall cut-throughs.
- BLUE: `blue_alpha` (304, 0, 60), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 260) on the east edge. Mirror facings.
- Spawns are inside cover (a crate) and face the process hall; a spawning player can choose to rush the hall, climb a stairway, or flank via the tank row or boiler row.

## Mode-specific

TDM carries no extra markers; the singleton `MapMarkers.TeamDeathmatch.INSTANCE` is used.

## Bot waypoints

Nine waypoints in a closed loop covering all three lanes: `wp_0` (-100, 0, 40) at the Distillation Tower, `wp_1` (0, 0, 40) at the centre tank, `wp_2` (100, 0, 40) at the east tank, `wp_3` (100, 0, 160) at Process Hall East, `wp_4` (0, 0, 160) at the Control Room, `wp_5` (-100, 0, 160) at Process Hall West, `wp_6` (-100, 0, 270) at Boiler West, `wp_7` (0, 0, 270) at Boiler Centre, `wp_8` (100, 0, 270) at Boiler East. The loop returns to `wp_0`. 60-tic period. The closed loop visits all three rows in order, which is the simplest way to give the bots a multi-lane patrol without pathfinding.

## Textures & Assets

- Level: `engine/src/main/resources/maps/refinery/level.ofm` — procedurally generated, committed via `git add -f`. 564 triangles, 1128 vertices, 2 textures. Floor and wall textures sourced from the Kenney Prototype Kit's colormap.png (CC0); pre-Pass 5 textures were procedural (a 64×64 worn-concrete floor and a 64×64 rusted-steel wall).
- Weapon: `assets/models/weapon/blaster-b.ofm` — the existing Kenney Blaster Kit `blaster-b` model, the player's standard viewmodel.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas; the builder accepts `-PrefineryAtlas=<colormap.png>`).

## Implementation status

- **FULL** (Pass 2; textures Kenney-ized in Pass 5). `Maps.refinery()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildRefineryMap` task and committed at `engine/src/main/resources/maps/refinery/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=refinery --fps=60"` boots and runs 120 tics without error.
