# crossroads — Crossroads

**Setting**: Desert Ravine
**Mode**: TDM
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A small desert town at a four-way crossroads. Three lanes run north–south through the town, the central plaza is the open contested ground at the intersection, and four corner buildings of the plaza are the named chokepoints. North of the plaza is a row of smaller "shack" buildings; south of the plaza is a row of larger "warehouse" buildings; the periphery is dry wash and sparse cactus. The map is the openest of the three shipped — long sightlines, few corners, sandstone cover rather than concrete — and it favours sniping and rotation over corner-clearing.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ┌──┐         ┌──┐         ┌──┐
         |  │A1│         │A2│         │A3│   Shack Row (lane A)
         |  │  │         │  │         │  │   z=24
         |  └──┘         └──┘         └──┘
         |
         |  ▓ wash channel (z=70-76) ▓
         |  ↑ cactus pair             ↑
         |
         |  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓
 LANE B  |  ▓  ┌──┐    ┌──┐    ┌──┐    ▓   Plaza (z=160)
  (M)    |  ▓  │NW│    │C │    │NE│    ▓   x=-64..64, z=128..192
         |  ▓  └──┘    │  │    └──┘    ▓   4 corner walls
         |  ▓  rock    └─────┘   rock  ▓   +
         |  ▓  ┌──┐    well       ┌──┐  ▓   2 wooden wells
         |  ▓  │SW│                │SE│  ▓
         |  ▓  └──┘                └──┘  ▓
         |  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓
         |
         |  ↑ cactus pair             ↑
         |  ▓ wash channel (z=250-256) ▓
         |
         |  ┌──────┐     ┌──────┐     ┌──────┐
         |  │  W1  │     │  W2  │     │  W3  │   Warehouse Row (lane C)
         |  │      │     │      │     │      │   z=296
         |  └──────┘     └──────┘     └──────┘
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160

         Red spawns                Blue spawns
         (16, _, 80..240)           (304, _, 80..240)
```

## Lanes

- **Lane A (North)** — the shack row. Four small (32×32×32) sandstone shacks at x = -112, -32, +32, +112 (z=24). The shacks are tight cover; the gaps between them (40 units wide) are the cut-throughs into the central plaza area.
- **Lane B (Middle)** — the central plaza at the four-way crossroads. A 64×64 open space (x=-64..64, z=128..192) with four low (24-tall) corner walls — the four named chokepoints. The plaza is the contested ground; the corner walls are the cover; the centre is open. Two wooden wells (one in the north half, one in the south half) mark the centre and break the long sight lines.
- **Lane C (South)** — the warehouse row. Four larger (48×48×72) sandstone warehouses at x = -112, -32, +32, +112 (z=296). The warehouses are taller and wider than the shacks, marking the south landmarks. The gaps between them (32 units wide) are the cut-throughs.

## Cut-throughs

- **A → B**: three cut-throughs between the four shacks (at x = -72, -8, +72), 40 units wide each. A player who flanks via lane A drops into the central plaza through one of these.
- **B → C**: three cut-throughs between the four warehouses (at x = -72, -8, +72), 32 units wide each. The warehouses are wider than the shacks, so the cut-throughs are narrower; a player who wants to push south has to pick the gap carefully.
- **Plaza cut-throughs**: the four corner walls of the plaza leave 24-unit-wide gaps on each of the four sides, so the plaza is open on the north, south, east, and west. A player can run straight through the plaza on the four cardinal axes.
- **Wash channels**: two long east-west wash walls (8-tall) at z=70-76 and z=250-256. These are not cut-throughs but cover — they break the long cross-map sight lines that the desert would otherwise have.

## Callouts

- `Shack Row West` (cp_a1) — the west shack at (-112, 24).
- `Shack Row Centre` (cp_a2) — the centre-left shack at (-32, 24) or centre-right at (+32, 24). The two central shacks are the most-defended north positions.
- `Shack Row East` (cp_a3) — the east shack at (+112, 24).
- `Cafe Corner` (cp_b1) — the NW plaza corner. The west-side plaza callout for RED.
- `Plaza Centre` (cp_b2) — the open centre of the plaza. The contested ground.
- `Sheriff's Office` (cp_b3) — the NE plaza corner. The east-side plaza callout for BLUE.
- `Wells Fargo` (cp_c1) — the west warehouse at (-112, 296).
- `Warehouse Row Centre` (cp_c2) — the centre warehouses at (-32, 296) and (+32, 296).
- `Trading Post` (cp_c3) — the east warehouse at (+112, 296).
- `Wash (N)` / `Wash (S)` — the two long wash walls.
- `Cactus Pair` — the two pairs of cacti at z=80 and z=240 (one pair on each side of the plaza).

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 80), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 240) on the west edge. Facings aimed at the plaza, slightly off-axis so a spawning player faces the cut-through at x=-72 between the shacks and the central plaza.
- BLUE: `blue_alpha` (304, 0, 80), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 240) on the east edge. Mirror facings.
- A spawning player is outside the wash channel cover and 16 units from the wall, so the wall provides immediate safety.

## Mode-specific

TDM carries no extra markers; the singleton `MapMarkers.TeamDeathmatch.INSTANCE` is used.

## Bot waypoints

Nine waypoints in a closed loop covering all three rows: `wp_0` (-112, 0, 24) at Shack Row West, `wp_1` (0, 0, 24) at the centre-left shack, `wp_2` (112, 0, 24) at Shack Row East, `wp_3` (64, 0, 160) at Sheriff's Office, `wp_4` (0, 0, 160) at Plaza Centre, `wp_5` (-64, 0, 160) at Cafe Corner, `wp_6` (-112, 0, 296) at Wells Fargo, `wp_7` (0, 0, 296) at the centre warehouse, `wp_8` (112, 0, 296) at Trading Post. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/crossroads/level.ofm` — procedurally generated, committed via `git add -f`. 444 triangles, 888 vertices, 2 textures. Floor and wall textures sourced from the Kenney Prototype Kit's colormap.png (CC0); pre-Pass 5 textures were procedural (a 64×64 sand-tone floor and a 64×64 sandstone wall).
- Weapon: `assets/models/weapon/blaster-b.ofm` — the existing Kenney Blaster Kit `blaster-b` model, the player's standard viewmodel.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas; the builder accepts `-PcrossroadsAtlas=<colormap.png>`).

## Implementation status

- **FULL** (Pass 3; textures Kenney-ized in Pass 5). `Maps.crossroads()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildCrossroadsMap` task and committed at `engine/src/main/resources/maps/crossroads/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=crossroads --fps=60"` boots and runs 120 tics without error.
