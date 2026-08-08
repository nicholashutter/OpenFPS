# hp-arctic — Subzero

**Setting**: Arctic Station
**Mode**: HARDPOINT
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A small radar-research outpost on a polar ice shelf. Three low sheet-metal buildings (the Generator Shed, the Operations Trailer, and the Fuel Depot) are the three Hardpoint zones, each a small enclosed space with one wide doorway. The buildings are connected by a system of snow-walled trenches at floor level, so a player who has dropped into a trench can rotate between buildings without being shot from above. The rotation sequence is Generator Shed → Operations Trailer → Fuel Depot, which moves the contested ground east to west over the course of a round. The map is the smallest of the four shipped — three buildings at the corners of a 96×96 triangle, with the trenches forming a Y in the middle.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_A              ║
         |  ║   (Generator Shed)       ║
         |  ║   z=64, x=64             ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |     ╲ (W trench,
         |      ╲  z=64..192, x=0)
         |
LANE B   |  ╔══════════════════════════╗
 (M)     |  ║  ●HP_ZONE_B              ║
         |  ║   (Operations Trailer)   ║
         |  ║   z=160, x=160           ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |     ╱ (E trench,
         |    ╱  z=128..256, x=0)
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_C              ║
         |  ║   (Fuel Depot)           ║
         |  ║   z=256, x=64            ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is built around three small buildings at the corners of a triangle, connected by trenches. The "lanes" are the three trench routes, and the rotation sequence replaces the A/B/C spatial structure.

- **W trench (y=0, x=0..32, z=64..192)** — the west trench, connecting the Generator Shed to the Operations Trailer. 8 units wide, snow walls on both sides.
- **E trench (y=0, x=0..32, z=128..256)** — the east trench, connecting the Operations Trailer to the Fuel Depot. Same shape as the W trench.
- **Floor (y=0, between the trenches)** — the open ground at the centre of the triangle, between the W and E trenches. The centre is the contested open ground; a player who has climbed out of a trench is exposed to all three buildings.

## Cut-throughs

- **Trench-floor exits** — each trench has two 8-unit-wide exits at the building ends, so a player can climb out of a trench and approach a building's doorway. The exits are the contested direct paths; the alternative is to stay in the trench and walk under the building's snow walls (where the trench passes through).
- **Building doorways** — each building has a single 16-unit-wide doorway on the side facing the centre. A player inside a building has line-of-sight to anyone approaching the doorway; a player outside the building has a single contested entry.
- **Floor open ground** — the open ground at the centre of the triangle (a 64×64 patch at z=128-192, x=64-128) is the only path that doesn't go through a trench. A player who is in the open is visible from all three buildings.

## Callouts

- `Generator Shed` — HP_ZONE_A, the first rotation. The round opens here.
- `Operations Trailer` — HP_ZONE_B, the contested middle. The round's pivotal zone.
- `Fuel Depot` — HP_ZONE_C, the third rotation. The round ends here.
- `W Trench` — the west trench route.
- `E Trench` — the east trench route.
- `Open Ground` — the centre of the triangle, the contested open ground.
- `Snow Wall (N)` / `Snow Wall (S)` — the long east-west snow walls marking the trench boundaries.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 64), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 256) on the west edge, facings aimed at the W trench entrance. A spawning player is 16 units from the trench mouth, with the trench's snow wall providing immediate safety.
- BLUE: `blue_alpha` (304, 0, 64), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 256) on the east edge, mirror facings.
- A team that wants the Generator Shed (HP_A) spawns on the west; a team that wants the Fuel Depot (HP_C) also spawns on the west. The round opens with both teams contesting the W trench.

## Mode-specific

- **HP_ZONE_A** (Generator Shed): centre (64, 0, 64), radius 32. Y elevation 0 (floor).
- **HP_ZONE_B** (Operations Trailer): centre (160, 0, 160), radius 32. Y elevation 0 (floor).
- **HP_ZONE_C** (Fuel Depot): centre (64, 0, 256), radius 32. Y elevation 0 (floor).
- Rotation period: 1800 tics (30 s at 60 Hz). Score-per-tick: 1.
- Rotation order: A → B → C → A → B → C ... (the round opens at the Generator Shed and ends at the Fuel Depot).

## Bot waypoints

Six waypoints in a closed loop covering the three buildings and the trench network: `wp_0` (64, 0, 64) inside the Generator Shed, `wp_1` (16, 0, 128) in the W trench, `wp_2` (160, 0, 160) inside the Operations Trailer, `wp_3` (16, 0, 192) in the E trench, `wp_4` (64, 0, 256) inside the Fuel Depot, `wp_5` (96, 0, 160) on the open ground at the centre of the triangle. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/arctic-hp/level.ofm` — Kenney-textured procedural geometry. Floor and wall tiles sampled from the Kenney Prototype Kit's `colormap.png` (CC0; `docs/ASSETS.md` § 3); accent submesh is a hand-authored solid red. The radar-mast detail in the centre of the triangle uses the accent texture.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas).

## Implementation status

- **FULL** (Pass 5). `Maps.arcticHp()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildSubzeroMap` task (with the optional `-PsubzeroAtlas=<colormap.png>` for the Kenney-textured build) and committed at `engine/src/main/resources/maps/arctic-hp/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=arctic-hp --fps=60"` boots and runs 120 tics without error. 312 triangles, 624 vertices, 3 textures.
