# ctf-stronghold — Stronghold

**Setting**: Desert Ravine
**Mode**: CTF
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A small sandstone fortress in the middle of a dry canyon. The fortress is square, with four corner towers and two gate towers on the east and west sides. The RED base sits inside the west gate, the BLUE base inside the east gate. The flag is a relic on a small pedestal in the centre of each base. The carrier's run from one gate to the other is roughly 320 units long, broken by the fortress's inner courtyard (a 96×96 open space with a low fountain in the centre) and the two flanking cliff walls. The cliff walls at z=64 and z=256 are 32-tall; a defender on the cliff can shoot down into the courtyard, but the cliff is exposed to the opposite cliff and to the courtyard's far side. The two gate towers are the natural chokepoints — a defender in the gate tower can cover the entire approach lane.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ─── ─── ─── cliff wall (z=64, y=32) ─── ─── ───
         |
         |  ╔════════════════════════════════════════╗
         |  ║  ●RED_BASE                            ║
         |  ║  (West Gate)                          ║
         |  ║  flag at (32, 32)                     ║
         |  ║  capture at (32, 32)                  ║
         |  ║  ●gate tower W                        ║
         |  ║  ●NW tower    ●SW tower               ║
         |  ╚══════════════════════════════════════╝
         |
LANE B   |          ┌────────────────────┐
 (M)     |          │   ●Fountain        │
         |          │   (Courtyard       │
         |          │    centre)         │
         |          └────────────────────┘
         |
         |  ╔════════════════════════════════════════╗
         |  ║  ●BLUE_BASE                           ║
         |  ║  (East Gate)                          ║
         |  ║  flag at (288, 288)                   ║
         |  ║  capture at (288, 288)                ║
         |  ║  ●NE tower    ●SE tower               ║
         |  ║  ●gate tower E                        ║
         |  ╚══════════════════════════════════════╝
         |
         |  ─── ─── ─── cliff wall (z=256, y=32) ─── ─── ───
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long east–west corridor; the "lanes" are the two cliff-wall flanking routes (north and south) and the central fortress courtyard.

- **Lane A (North)** — the cliff-top flanking route. A 16-unit-wide path at y=32, from x=0 to x=320 along z=64. The cliff is exposed from below and from the opposite cliff; a defender on the cliff is in cover from the courtyard but visible to snipers on the south cliff. The cliff has a stairway down to the courtyard at x=160.
- **Lane B (Middle)** — the fortress courtyard. The 96×96 open space at y=0, from x=112 to x=208 and from z=112 to z=208. The fountain at the centre is the only cover; the courtyard is otherwise open. The carrier has to pass through the courtyard (or around it via the cliff) to reach the enemy gate.
- **Lane C (South)** — the south cliff. Mirror of Lane A, at z=256.

## Cut-throughs

- **Gate towers (E & W)** — the two gate towers are 16-tall, 16×16 sandstone blocks at (16, 0, 160) and (304, 0, 160). The gate tower is a covered position; a defender in the gate tower can shoot through the gate and into the courtyard. The gate itself is a 16-unit-wide opening at the base of the tower.
- **Cliff stairways** — two stairways from the cliff tops (y=32) down to the courtyard (y=0) at (160, 32, 64) and (160, 32, 256). A defender on the cliff can rotate to the courtyard in 8 treads.
- **Courtyard archways** — the courtyard has two 16-unit-wide archways at the north and south sides, at z=112 and z=208, x=112-208. The archways are the only way into the courtyard from the cliff top; a player who drops off the cliff without using the archway lands in the cliff shadow and is exposed.
- **Tower roof access** — the four corner towers (NW, NE, SW, SE) are 32-tall, each 16×16. A defender on the tower roof can see the gate approach, the cliff top, and the courtyard. The tower roof is reached via internal stairway (Pass 4+ — Pass 3 design has the towers as solid blocks).

## Callouts

- `Red Base` / `West Gate` — RED's base at (32, 32). The flag and capture point sit at the same place.
- `Blue Base` / `East Gate` — BLUE's base at (288, 288). Mirror.
- `Courtyard` — the central 96×96 open space with the fountain.
- `Fountain` — the courtyard's central feature; the only cover in the courtyard.
- `Gate Tower (W)` / `Gate Tower (E)` — the two gate towers, the chokepoints.
- `Tower (NW)` / `Tower (NE)` / `Tower (SW)` / `Tower (SE)` — the four corner towers.
- `Cliff (N)` / `Cliff (S)` — the two flanking cliff walls.
- `Stairway (N)` / `Stairway (S)` — the two cliff-to-courtyard stairways.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 32), `red_bravo` (16, 0, 64), `red_charlie` (16, 0, 96) on the west edge, facings aimed at the West Gate. The RED spawns are outside the fortress, so a spawning player is in the open for the first 16 units before reaching the gate.
- BLUE: `blue_alpha` (304, 0, 224), `blue_bravo` (304, 0, 256), `blue_charlie` (304, 0, 288) on the east edge, mirror facings.
- Each team's spawns are outside the fortress on the gate side, so a defender who has spawned can either run to the flag, climb the cliff, or wait at the gate for the carrier.

## Mode-specific

- **RED Base**: flag at (32, 0, 32), capture at (32, 0, 32), radius 32. The flag and the capture point sit at the same place.
- **BLUE Base**: flag at (288, 0, 288), capture at (288, 0, 288), radius 32.
- Pickup radius for the enemy flag: 32. Drop-on-death: automatic (returns to base after 30 s, in Pass 4+).
- Score: 1 per capture. Time limit: 10 minutes. Capture limit: 5.
- The round opens with both flags at home. A team that wants to play defensive spawns at the cliff, watches the courtyard from above, and waits for the carrier to commit; a team that wants to play aggressive spawns at the gate and pushes straight into the courtyard.

## Bot waypoints

Six waypoints in a closed loop: `wp_0` (16, 0, 32) at RED's spawn, `wp_1` (16, 0, 160) at the West Gate, `wp_2` (160, 0, 160) at the courtyard fountain, `wp_3` (304, 0, 160) at the East Gate, `wp_4` (304, 0, 288) at BLUE's spawn, `wp_5` (32, 0, 256) on the south cliff approach. The loop returns to `wp_0`. 60-tic period. (Bots in CTF do not pick up flags in Pass 3 — they patrol as defenders and only the local player carries the flag.)

## Textures & Assets

- Level: `engine/src/main/resources/maps/stronghold/level.ofm` (Pass 4 — procedurally generated like Crossroads). Sandstone walls (8-band horizontal block lines) plus a 64×64 sand-tone courtyard texture (sparse dark streaks, like the butte top).
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 7). `Maps.stronghold()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildStrongholdMap` task and committed at `engine/src/main/resources/maps/stronghold/level.ofm`. The CTF mode logic is **fully implemented** in `Match.updateCtf` and tested by `MatchCtfTest` (16 tests) — pickup, drop-on-death, return-on-touch, and capture-on-touch, with both flags returning to their bases on a save or capture. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=stronghold --fps=60"` boots and runs 120 tics without error. 168 triangles, 336 vertices, 2 textures.
