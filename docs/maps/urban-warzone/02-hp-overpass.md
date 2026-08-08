# hp-overpass — Overpass

**Setting**: Urban Warzone
**Mode**: HARDPOINT
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A highway interchange at street level: two parallel overpasses running east–west with a service road between them, and a control building anchoring the south. The two bridges are the high ground — the player who holds them controls the long sightlines, but is exposed from below. The service road is the low ground — cover, but no sightlines. The control building is the chokepoint that decides which team gets the third zone. Three hardpoint zones rotate: one on each overpass, then the control building.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
 NORTH   |  ╔═══════════════════╗       ╔═══════════════════╗
 OVERPASS|  ║  ●HP_ZONE_A        ║       ║                   ║
         |  ║   (Overpass N)     ║       ║                   ║
         |  ╚════════════════════╝       ╚════════════════════╝
         |        Service Road (low ground)
         |       z=120 ─────  z=200 (cover: cars, cones)
         |  ╔═══════════════════╗       ╔═══════════════════╗
 SOUTH   |  ║                   ║       ║                   ║
 OVERPASS|  ║  ●HP_ZONE_B        ║       ║                   ║
         |  ║   (Overpass S)     ║       ║                   ║
         |  ╚════════════════════╝       ╚════════════════════╝
         |
         |                  ┌──────────────────────┐
         |                  │  ●HP_ZONE_C          │
         |                  │   (Control Building) │
         |                  └──────────────────────┘
         +─────────────────────────────────────→  +X (right in 2D)
         x=0                            x=320
```

## Lanes

The map is **not** a three-lane layout in the COD sense. The two overpasses run east–west (the `EAST_WEST` axis) and the rotation sequence replaces the lane structure. There are no A/B/C lanes here.

- **High lane (north overpass)** — the elevated east-west route from x=0 to x=320 at z=40. The deck is 16 units wide; sightlines are clean to the south overpass and to the control building. The capture zone is on the deck, centre of the overpass.
- **Mid corridor (service road)** — the open low ground from x=0 to x=320, between z=120 and z=200. Cars and concrete barriers provide cover. The mid is the contested ground between the two overpasses.
- **High lane (south overpass)** — the elevated east-west route at z=240. Same shape as the north overpass.
- **South anchor (control building)** — a 96×64 building at x=128, z=296. The third hardpoint zone is inside it.

## Cut-throughs

- The service road connects the overpasses at x=16 (west ramp) and x=304 (east ramp) — these are the natural rotations from one overpass to the other.
- The control building is reached from the south overpass via a stairway at x=192, z=224.

## Callouts

- `Overpass N` — the north overpass; the second rotation zone.
- `Service Road` — the central low ground.
- `Overpass S` — the south overpass; the first rotation zone (opens on it).
- `Control Building` — the south anchor; the third rotation zone.
- `West Ramp`, `East Ramp` — the two overpass transitions.
- `Stairway` — the overpass-to-control-building path.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 64), `red_bravo` (16, 0, 96), `red_charlie` (16, 0, 128) on the west side, facings aimed at the west ramp and the overpasses.
- BLUE: `blue_alpha` (304, 0, 192), `blue_bravo` (304, 0, 224), `blue_charlie` (304, 0, 256) on the east side, mirror facings.
- Spawns face the ramps — the rotation's first zone (Overpass S) is on the south overpass, so a spawning player is one ramp away from the first capture.

## Mode-specific

- **HP_ZONE_A** (Overpass N): centre (160, 64, 40), radius 48.
- **HP_ZONE_B** (Overpass S): centre (160, 64, 240), radius 48.
- **HP_ZONE_C** (Control Building): centre (160, 0, 296), radius 48.
- Rotation period: 1800 tics (30 s at 60 Hz). Score-per-tick: 1.
- Rotation order: B → A → C → B → A → C ...

## Bot waypoints

Six waypoints in a closed loop: `wp_0` (160, 0, 240) on Overpass S, `wp_1` (192, 0, 220) at the top of the east ramp, `wp_2` (304, 0, 160) on the east service road, `wp_3` (160, 0, 40) on Overpass N, `wp_4` (16, 0, 160) on the west service road, `wp_5` (128, 0, 220) at the bottom of the west ramp. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/overpass/level.ofm` — a procedurally generated 320×320 model, committed as a deliberate small fixture (`git add -f` overrides the `*.ofm` gitignore rule). **384 triangles, 768 vertices, 3 textures.** Geometry: 1 ground slab + 1 service road + 2 overpass decks (raised at y=64) + 4 low perimeter walls (half-height, so the elevated overpasses still read as bridges) + 8 underdeck support pillars + 4 ramp walls (two boxes each, stepping from y=0 to y=64) + 1 control building (96x64, south) + 1 stairway + 6 concrete barriers on the service road + 4 signposts in the accent submesh. Floor and wall textures are sampled from the Kenney Prototype Kit's `colormap.png`; the accent texture is a hand-authored solid red.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: the Kenney Prototype Kit's `colormap.png` (CC0, already in the repo at `assets/gltf/level/Textures/colormap.png`).

## Implementation status

- **FULL** (Pass 2). `Maps.overpass()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildOverpassMap` task (with the optional `-PoverpassAtlas=<colormap.png>` argument for Kenney textures) and committed at `engine/src/main/resources/maps/overpass/level.ofm`. The Hardpoint mode logic is **fully implemented** in `Match.updateHardpoint` and tested by `MatchHardpointTest` (13 tests) — three zones rotate every 1800 tics, the active zone is captured by whoever is in its radius, and the per-tic scoring awards one point to the holder. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=overpass --fps=60"` boots and runs 120 tics without error.
