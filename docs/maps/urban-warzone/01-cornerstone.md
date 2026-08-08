# cornerstone — Cornerstone

**Setting**: Urban Warzone
**Mode**: TDM
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A rectangular city block: concrete and glass, painted steel, parked vehicles. Three lanes run north–south across the map, with the central `Market` row acting as the risk/reward mid lane. Two landmark buildings anchor each end of lanes A and C; the central `Bridge` plaza anchors lane B's middle. The map is wider than the demo room (320 units vs. ~60) and is built to be the first thing a player walks into when they want a real three-lane COD match, not a one-room showcase.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |
 LANE A  |  CAFE●                     ●LIBRARY
  (N)    | ┌──┐                       ┌──┐
         | │  │  ════════════════════ │  │        z=0
         | │  │   ┌──────┐ ┌──────┐   │  │
         | │  │   │      │ │      │   │  │
         | │  │   │      │ │      │   │  │
 LANE B  | └──┘   └──────┘ └──────┘   └──┘
  (M)    | PLAZA            BRIDGE●      ATRIUM
         |       cut-throughs                z=160
         | ┌──┐   ┌──────┐ ┌──────┐   ┌──┐
         | │  │   │      │ │      │   │  │
         | │  │   │      │ │      │   │  │
         | │  │   └──────┘ └──────┘   │  │        z=320
 LANE C  | STOREFRONT●                  PLAZA●
  (S)    | ┌──┐                       ┌──┐
         | │  │                       │  │
         | └──┘                       └──┘
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=0                            x=320

         Red spawns                Blue spawns
         (16, _, 64..192)            (304, _, 128..256)
```

## Lanes

- **Lane A (North)** — the safest lane. `Cafe` (x≈64) and `Library` (x≈192) are the two landmark buildings; `Plaza` (x=128) is the cross-lane plaza where lane A meets lane B. The southern A/B boundary wall is broken by two cut-throughs at x=96 and x=224.
- **Lane B (Middle)** — the risk/reward mid lane. `Bridge` (x=64), `Market` (x=160), and `Atrium` (x=256) are the named chokepoints. The lane runs from x=64 to x=256 with a 4-deep row of crates (8 crates in two stacks of 4) along the centre line as the only mid-lane cover. Crossing from end to end with no cover costs you.
- **Lane C (South)** — the south lane, mirroring lane A. `Storefront` (x=64) and `Plaza` (x=256, the southern one) are the landmark buildings; `Alley` (x=160) is the cross-lane point. The B/C boundary wall has a single cut-through at x=160.

## Cut-throughs

- A → B: two cut-throughs at x=96 and x=224 in the z=100 wall.
- B → C: one cut-through at x=160 in the z=220 wall.
- Each cut-through is 12 units wide, 6-unit-thick wall on either side, and aligned with the named chokepoints so a player can read "I'm at Cafe, I can go to Market via the east cut-through" from a glance.

## Callouts

- `Cafe` — west landmark of lane A. The single most-used spawn-side cover on red.
- `Library` — east landmark of lane A.
- `Plaza` (north) — the open space at (128, 80) where lane A meets the A/B boundary.
- `Bridge` — west chokepoint of lane B; players crossing from red's spawn to the middle call out "they're at Bridge".
- `Market` — central chokepoint of lane B; the long east-west wall is the cover here.
- `Atrium` — east chokepoint of lane B, east landmark.
- `Storefront` — west landmark of lane C.
- `Alley` — the cross-lane point at (160, 240).
- `Plaza` (south) — the open space at (256, 296), blue's spawn-side landmark.

## Spawns

- 6 spawn points total — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 64) facing east, `red_bravo` (16, 0, 128) facing east-south-east, `red_charlie` (16, 0, 192) facing south-east. All on the west edge, facings aimed at the lane A south wall (so a spawning player faces Cafe directly, but the wall is in the way).
- BLUE: `blue_alpha` (304, 0, 128), `blue_bravo` (304, 0, 192), `blue_charlie` (304, 0, 256) on the east edge with mirror facings.
- Three spawns per team is the CO6 sweet spot: enough to rotate around, not so many that "spawn camping" is impossible.

## Mode-specific

TDM carries no extra markers; the singleton `MapMarkers.TeamDeathmatch.INSTANCE` is used.

## Bot waypoints

Eight waypoints forming a closed loop around the map: starting at the lane-A north-west corner (`wp_0`), running east to `wp_1` (160, 0, 24), south-east to `wp_2` (192, 0, 80) at the A/B boundary, south to `wp_3` (160, 0, 160) in the middle of lane B, east to `wp_4` (256, 0, 160), south to `wp_5` (192, 0, 240) at the B/C boundary, south to `wp_6` (160, 0, 296) in lane C, and west to `wp_7` (80, 0, 296) at the south-west corner. The loop returns to `wp_0`. A bot walks from waypoint `i` to waypoint `i+1` (wrapping at the end), with a 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/cornerstone/level.ofm` — a procedurally generated 320×320 model, committed as a deliberate small fixture (`git add -f` overrides the `*.ofm` gitignore rule). **348 triangles, 696 vertices, 3 textures.** The geometry is unchanged in spirit from Pass 1 (1 floor slab + 4 perimeter walls + 2 internal walls with cut-throughs + 4 landmark buildings + 8 mid-lane crates), with a Pass 2 addition of **8 streetlight bollards and 4 corner-trim pieces** on a third "accent" submesh so the spawn edges and the landmark rooftops have visible detail. Floor and wall textures are sampled from the Kenney Prototype Kit's `colormap.png` (CC0; `docs/ASSETS.md` § 3) — the floor pulls row 0 column 0 (light grey, the kit's floor-square colour) and the wall pulls row 2 column 0 (dark grey, the kit's wall colour). The accent texture is a hand-authored solid red (no equivalent in the kit). Without the atlas the builder falls back to the previous procedural generator.
- Weapon: `assets/models/weapon/blaster-b.ofm` — the existing Kenney Blaster Kit `blaster-b` model, the player's standard viewmodel. 368 triangles.
- Atlas: the Kenney Prototype Kit's `colormap.png` (CC0, already in the repo at `assets/gltf/level/Textures/colormap.png`).

## Implementation status

- **FULL** (Pass 1, extended by Pass 2). `Maps.cornerstone()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildCornerstoneMap` task (with the optional `-PcornerstoneAtlas=<colormap.png>` argument to use Kenney textures) and committed at `engine/src/main/resources/maps/cornerstone/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"` boots and runs 120 tics without error. The windowed test `.\gradlew.bat :desktop:run --args="--map=cornerstone --start-in-game"` opens a window, binds the level scene to the renderer, and runs the per-tic map-driven gameplay.
