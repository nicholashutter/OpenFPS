# icebridge — Icebridge

**Setting**: Arctic Station
**Mode**: TDM
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A frozen-bridge highway rest stop on a polar road. Two long east–west bridges (the North Bridge and the South Bridge) span a frozen ravine, with a service building anchoring the south end and a fuel-depot row anchoring the north end. The bridges are the high ground — a player on a bridge controls the long sightline across the ravine, but is exposed from below. The ravine floor (y=-8) is the low ground — snowdrift cover, no sightlines, the contested path between the two bridges. The service building is the chokepoint that decides which team controls the southern end. The whole map is the cleanest sightline map of the four shipped: the two bridges are 64 units apart, so a player on one bridge can see the other bridge from end to end.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔═══════════╗       ╔═══════════╗
         |  ║           ║       ║           ║
NORTH     |  ║  Fuel     ║       ║  Fuel     ║
BRIDGE   |  ║  Depot    ║       ║  Depot    ║
         |  ║  West     ║       ║  East     ║
         |  ║           ║       ║           ║
         |  ╚═══════════╝       ╚═══════════╝
         |   z=24..56, y=32 (the high ground — the North Bridge)
         |  ─────  snowdrift (z=80..88)  ─────
         |
         |  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
LANE B   |  ░░░ FROZEN RAVINE (y=-8..0) ░░░
 (M)     |  ░░░ the low ground — snowdrift  ░░░
         |  ░░░ cover, no sightlines        ░░░
         |  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
         |
         |   z=200..232, y=32 (the high ground — the South Bridge)
         |  ╔═══════════════════════════════╗
SOUTH     |  ║                               ║
BRIDGE   |  ║       SERVICE BUILDING        ║
         |  ║       (the southern chokepoint)║
         |  ║                               ║
         |  ╚═══════════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160

         Red spawns                Blue spawns
         (16, _, 200..280)          (304, _, 200..280)
```

## Lanes

The map is **not** a three-lane layout in the COD sense. The two bridges run east–west and the rotation/spatial structure is high-ground (bridges) vs low-ground (ravine) vs south anchor (service building). There is no third A/B/C lane — the ravine floor is the middle, and the two bridges are the two flanks.

- **North Bridge (y=32, z=24..56)** — the elevated east–west route from x=-160 to x=160 at z=40. The deck is 32 units wide; sightlines are clean to the South Bridge and to the ravine floor. Two fuel-depot buildings sit on the bridge at the west and east ends (anchors for the north positions).
- **Ravine Floor (y=-8, z=88..192)** — the frozen ravine that runs between the two bridges. The floor is 8 units below the surrounding ground (y=-8), and the snowdrift cover is waist-high (16 units). The ravine is the contested low ground; a player in the ravine is hidden from the bridges but has no sightlines of their own.
- **South Bridge (y=32, z=200..232)** — the elevated east–west route at z=216, mirror of the North Bridge. The service building sits on the south side of this bridge.
- **Service Building (y=0..32, z=232..296)** — the southern anchor; a 64×64 sheet-metal building with a fuel-pump canopy on the east side.

## Cut-throughs

- **Ravine ramp (W)** — a 16% grade ramp from the west ground at (-160, 0, 144) down to the ravine floor at (-160, -8, 144). The contested western entry to the ravine.
- **Ravine ramp (E)** — mirror of (W) on the east side at (160, 0, 144).
- **Bridge service stairs (W & E)** — two stairways from the North Bridge (y=32) down to the ground (y=0) at x=±144, z=56. The way to drop from the North Bridge to the floor.
- **Service building exits (N & S)** — two exits from the service building at (32, 0, 232) and (288, 0, 232) leading to the South Bridge. A player inside the service building can climb onto the South Bridge through either.

## Callouts

- `North Bridge` — the north elevated route. The signature high ground.
- `South Bridge` — the south elevated route. The other high ground.
- `Ravine (W)` / `Ravine (E)` — the two ends of the frozen ravine.
- `Snowdrift Mid` — the centre snowdrift at (0, 0, 140), the centre of the ravine.
- `Fuel Depot (W)` / `Fuel Depot (E)` — the two fuel buildings on the North Bridge.
- `Service Building` — the south anchor, the chokepoint.
- `Fuel Pump Canopy` — the east-side canopy on the service building.
- `Service Stairs (N)` / `Service Stairs (S)` — the two stairways down from the South Bridge.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 200), `red_bravo` (16, 0, 240), `red_charlie` (16, 0, 280) on the west edge, facings aimed at the service building. A spawning player is 16 units from the service building's west wall.
- BLUE: `blue_alpha` (304, 0, 200), `blue_bravo` (304, 0, 240), `blue_charlie` (304, 0, 280) on the east edge, mirror facings.
- A team that wants the South Bridge spawns on the west or east and rotates onto the bridge; a team that wants the North Bridge spawns on the same edges and rotates north through the ravine. The spawn choice is symmetric — both teams start on the south, and the round opens with RED and BLUE contesting the service building.

## Mode-specific

TDM carries no extra markers; the singleton `MapMarkers.TeamDeathmatch.INSTANCE` is used.

## Bot waypoints

Nine waypoints in a closed loop covering the bridges and the ravine: `wp_0` (-128, 32, 40) at the west end of the North Bridge, `wp_1` (0, 32, 40) at the centre of the North Bridge, `wp_2` (128, 32, 40) at the east end of the North Bridge, `wp_3` (128, 0, 144) on the ravine floor at the east end, `wp_4` (0, -8, 144) at Snowdrift Mid, `wp_5` (-128, 0, 144) on the ravine floor at the west end, `wp_6` (-128, 32, 216) at the west end of the South Bridge, `wp_7` (0, 32, 216) at the centre of the South Bridge, `wp_8` (128, 32, 216) at the east end of the South Bridge. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/arctic-station/level.ofm` — procedurally generated, committed via `git add -f`. 300 triangles, 600 vertices, 2 textures. Floor and wall textures sourced from the Kenney Prototype Kit's colormap.png (CC0); pre-Pass 5 textures were procedural (a 64×64 snow-tone floor and a 64×64 sheet-metal wall).
- Weapon: `assets/models/weapon/blaster-b.ofm` — the existing Kenney Blaster Kit `blaster-b` model, the player's standard viewmodel.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas; the builder accepts `-ParcticStationAtlas=<colormap.png>`).

## Implementation status

- **FULL** (Pass 4; textures Kenney-ized in Pass 5). `Maps.arcticStation()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildArcticStationMap` task and committed at `engine/src/main/resources/maps/arctic-station/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=arctic-station --fps=60"` boots and runs 120 tics without error.
