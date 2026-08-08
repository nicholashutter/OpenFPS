# ctf-storage — Storage

**Setting**: Industrial Complex
**Mode**: CTF
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A chemical storage facility: two large warehouse buildings at opposite ends of the map, each with its team's flag inside. Between the warehouses is a maze of storage tanks, pipe racks, and access catwalks. The carrier's run from one base to the other is roughly 280 units long, broken by the central maze into three segments: leaving the home warehouse, crossing the maze, and entering the enemy warehouse. The catwalks at y=64 let a defender rotate quickly between the maze and the flag, and a flanker can reach the carrier's exit before the carrier has cleared the home warehouse.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●RED_BASE              ║
         |  ║  (Red Warehouse)        ║
         |  ║  flag at (32, 32)      ║
         |  ║  capture at (32, 32)   ║
         |  ╚══════════════════════════╝
         |
         |  ───── catwalk at y=64 (N-S, x=0) ─────
         |
         |  ┌──┐  ┌──┐  ┌──┐  ┌──┐
         |  │T1│  │T2│  │T3│  │T4│   (storage tanks, lane B)
 LANE B  |  │  │  │  │  │  │  │  │  │   the maze
  (M)    |  │  │  │  │  │  │  │  │  │
         |  └──┘  └──┘  └──┘  └──┘
         |
         |  ┌──┐  ┌──┐  ┌──┐  ┌──┐
         |  │T5│  │T6│  │T7│  │T8│
         |  │  │  │  │  │  │  │  │
         |  └──┘  └──┘  └──┘  └──┘
         |
         |  ───── catwalk at y=64 (N-S, x=0) continues ─────
         |
         |  ╔══════════════════════════╗
         |  ║  ●BLUE_BASE             ║
         |  ║  (Blue Warehouse)       ║
         |  ║  flag at (288, 288)    ║
         |  ║  capture at (288, 288) ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long east–west corridor with the maze of storage tanks in the middle. The "lanes" are the two warehouse approaches (north and south) and the central maze.

- **North approach** — the open ground from the RED warehouse at (32, 32) to the maze at z=120. Long, clean sightlines, low cover.
- **Maze** — the eight storage tanks arranged in two rows of four, at z=120-200. The contested centre; the carrier has to pass through this to score.
- **South approach** — the open ground from the BLUE warehouse at (288, 288) to the maze at z=200. Mirror of the north approach.

## Cut-throughs

- **Tank-row gaps** — the maze has gaps at x=-50 and x=+50 in both rows, so a carrier can slip between the tanks. The gaps are the contested direct paths; the alternative is to climb the catwalk at x=0 and walk the central spine.
- **Catwalk spine** — a single north–south catwalk at x=0, y=64, running the length of the map from the RED warehouse to the BLUE warehouse. A carrier on the catwalk is visible from the floor, but the catwalk is the only way to skip the maze.
- **Warehouse exits** — each warehouse has a wide front entrance (16 units across) on the side facing the maze. A carrier exiting the warehouse is in the open for the first 16 units.

## Callouts

- `Red Warehouse` — RED's base, at (32, 32). The flag and capture point sit at the same place.
- `Blue Warehouse` — BLUE's base, at (288, 288). Mirror.
- `Tank Row (N)` — the north row of four storage tanks, z=120-160.
- `Tank Row (S)` — the south row of four storage tanks, z=200-240.
- `Catwalk Spine` — the central catwalk at x=0, y=64, running the length of the map.
- `West Gap` / `East Gap` — the gaps between the tanks in each row.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 32), `red_bravo` (16, 0, 64), `red_charlie` (16, 0, 96) on the west edge, facings aimed at the RED warehouse.
- BLUE: `blue_alpha` (304, 0, 224), `blue_bravo` (304, 0, 256), `blue_charlie` (304, 0, 288) on the east edge, facings aimed at the BLUE warehouse.
- Each team's spawns are inside the team's own warehouse. A defender who has spawned in the warehouse can run straight to the flag, climb the catwalk to the spine, or wait at the warehouse entrance for the carrier to come home.

## Mode-specific

- **RED Base**: flag at (32, 0, 32), capture at (32, 0, 32), radius 32. The flag and the capture point sit at the same place.
- **BLUE Base**: flag at (288, 0, 288), capture at (288, 0, 288), radius 32.
- Pickup radius for the enemy flag: 32. Drop-on-death: automatic (returns to base after 30 s, in Pass 4+).
- Score: 1 per capture. Time limit: 10 minutes. Capture limit: 5.

## Bot waypoints

Six waypoints in a closed loop: `wp_0` (16, 0, 32) inside the RED warehouse, `wp_1` (16, 0, 160) west of the maze, `wp_2` (16, 0, 288) west of the BLUE warehouse, `wp_3` (304, 0, 288) inside the BLUE warehouse, `wp_4` (304, 0, 160) east of the maze, `wp_5` (304, 0, 32) east of the RED warehouse. The loop returns to `wp_0`. 60-tic period. (Bots in CTF do not pick up flags in Pass 1 — they patrol as defenders, and only the local player carries the flag.)

## Textures & Assets

- Level: `assets/maps/storage/level.ofm` (Pass 4).
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 7). `Maps.storage()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildStorageMap` task and committed at `engine/src/main/resources/maps/storage/level.ofm`. The CTF mode logic is **fully implemented** in `Match.updateCtf` and tested by `MatchCtfTest` (16 tests) — pickup, drop-on-death, return-on-touch, and capture-on-touch, with both flags returning to their bases on a save or capture. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=storage --fps=60"` boots and runs 120 tics without error. 192 triangles, 384 vertices, 2 textures.
