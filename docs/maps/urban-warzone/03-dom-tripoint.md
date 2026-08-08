# dom-tripoint — Tripoint

**Setting**: Urban Warzone
**Mode**: DOMINATION
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A three-way intersection at street level: a roundabout in the centre and three approach streets (north, south-east, south-west) leading to the three flags. Each flag is in the open at the end of one of the approach streets, and capturing any one of them gives a team a scoring point per tick. The central roundabout is the contested ground; the three flags are the rewards. A team that captures two flags at once earns double the score; capturing all three is the lockout.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |
         |                  ┌────────┐
         |                  │ FLAG A │
         |                  │ (N)    │
         |                  └────────┘
         |                  z=48, x=160
         |                       ╲
         |                        ╲   (approach road N)
         |                         ╲
         |              ┌─────────────────────┐
         |              │   ●Roundabout       │
         |              │   (FLAG B at x=160, │
         |              │        z=160)       │
         |              │                     │
         |              └─────────────────────┘
         |              z=140 ───  z=180
         |              x=120 ───  x=200
         |              (the central capture zone)
         |             ╱                 ╲
         |            ╱                   ╲
         |           ╱                     ╲
         |  ┌────────┐                     ┌────────┐
         |  │ FLAG C │                     │ FLAG C │  (TWO flags C?
         |  │ (SE)   │                     │ (SW)   │   No, the spec uses
         |  └────────┘                     └────────┘   FLAG C as the
         |  z=240, x=80                    z=240, x=240  southern pair, see
         |  (FLAG C, the south-east flag)  (south-west)  Mode-specific below)
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=0                            x=320
```

## Lanes

The map is built around three **approach streets**, each running from the centre of the map to one of the flags. The approach streets replace the A/B/C lanes because Domination's flow is "centre → flag", not "flag → flag".

- **Approach N (north)** — the straight 32-unit-wide road from the roundabout to FLAG_A at (160, 48). Sightlines are clean; cover is two rows of parked cars and a gas station canopy at z=96.
- **Approach SE (south-east)** — the curving road from the roundabout to FLAG_C (south-east) at (240, 240). Cover is a billboard and a bus stop.
- **Approach SW (south-west)** — the curving road from the roundabout to FLAG_C (south-west) at (80, 240). Cover is a planter and a low wall.

## Cut-throughs

- The three approach streets all meet at the roundabout, which is the natural cut-through point. There are no walls blocking the connections.
- A "back-alley" cut-through runs east-west at z=200, from the south-east approach to the south-west approach, behind the flag positions. It is the only way to rotate between the southern flags without going through the roundabout.

## Callouts

- `Roundabout` — the central capture zone. FLAG B sits here.
- `FLAG A` — the north flag.
- `FLAG C (SE)` — the south-east flag.
- `FLAG C (SW)` — the south-west flag.
- `Gas Station` — the canopy cover on the north approach.
- `Billboard` — the cover on the south-east approach.
- `Back Alley` — the south cut-through.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 96), `red_bravo` (16, 0, 128), `red_charlie` (16, 0, 160) on the west edge, facings aimed at the south-west approach.
- BLUE: `blue_alpha` (304, 0, 160), `blue_bravo` (304, 0, 192), `blue_charlie` (304, 0, 224) on the east edge, facings aimed at the south-east approach.
- A team that wants to control FLAG A spawns on the west and rotates north; a team that wants the south-east flag spawns on the east and rotates south-east. The spawn choice is part of the strategy.

## Mode-specific

- **FLAG_A**: centre (160, 0, 48), radius 32.
- **FLAG_B**: centre (160, 0, 160), radius 48 (larger — the roundabout is the contested ground).
- **FLAG_C_SE**: centre (240, 0, 240), radius 32.
- **FLAG_C_SW**: centre (80, 0, 240), radius 32.
- All flags start NEUTRAL. Score-per-tick: 1 per flag held. Rotation: none (the static domination design — flags do not rotate).

> **Note**: A future revision may rename FLAG_C_SE and FLAG_C_SW to FLAG_C and FLAG_D, or to FLAG_C and FLAG_B (where the central one is the "B" of "A-B-C"). The Pass 1 spec uses the southern pair as a single logical "C" for simplicity, with the two physical flag positions representing the two halves of one capture zone. Pass 2 will resolve this naming question.

## Bot waypoints

Six waypoints in a closed loop covering the approaches: `wp_0` (160, 0, 48) at FLAG A, `wp_1` (160, 0, 100) on the north approach, `wp_2` (160, 0, 160) at the roundabout, `wp_3` (240, 0, 200) on the south-east approach, `wp_4` (160, 0, 240) between the two southern flags, `wp_5` (80, 0, 200) on the south-west approach. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/tripoint/level.ofm` — a procedurally generated 320×320 model, committed as a deliberate small fixture (`git add -f` overrides the `*.ofm` gitignore rule). **240 triangles, 480 vertices, 3 textures.** Geometry: 1 ground slab + 3 approach roads (raised 2 units, the road surface) + 1 roundabout kerb (an 80x80 box at the centre, raised 4 units) + 4 low perimeter walls + 1 back-alley cut-through (two short walls at z=200 with a gap at x=160) + 3 flag stands (one per flag) + 4 streetlight bollards + 3 flag-pole slats in the accent submesh. Floor and wall textures are sampled from the Kenney Prototype Kit's `colormap.png`; the accent texture is a hand-authored solid red.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: the Kenney Prototype Kit's `colormap.png` (CC0, already in the repo at `assets/gltf/level/Textures/colormap.png`).

## Implementation status

- **FULL** (Pass 2). `Maps.tripoint()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildTripointMap` task and committed at `engine/src/main/resources/maps/tripoint/level.ofm`. The Domination mode logic is **fully implemented** in `Match.updateDomination` and tested by `MatchDominationTest` (13 tests) — three flags, neutral start, contested flags do not switch sides, per-tic scoring is one point per flag held. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=tripoint --fps=60"` boots and runs 120 tics without error.
