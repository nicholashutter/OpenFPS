# dom-sandbar — Sandbar

**Setting**: Desert Ravine
**Mode**: DOMINATION
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A wide, shallow canyon that runs east–west with a deep dry riverbed in the centre. The three flags are the three exposed sandstone buttes that rise from the canyon floor: FLAG_C in the north butte, FLAG_B in the central butte (the largest, the round's pivotal flag), and FLAG_A in the south butte. Each butte is a small mesa — flat-topped, 32-tall, with one easy ramp and a few cacti on top. The canyon's long east–west sightline is broken by the buttes themselves, so a player who controls a butte has the high ground and the cover. The dry riverbed in the centre is the contested ground at the start; the round opens with RED and BLUE pushing toward the buttes from the canyon walls.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●FLAG_C                 ║
         |  ║   (Butte N)              ║
         |  ║   z=64, x=160            ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |     ╲ (N ramp, x=0..32,
         |      ╲  8 treads)
         |
LANE B   |  ╔══════════════════════════╗
 (M)     |  ║  ●FLAG_B                 ║
         |  ║   (Butte Centre)         ║
         |  ║   z=160, x=160           ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |     ╱ (S ramp, x=0..32,
         |    ╱  8 treads)
         |
         |  ╔══════════════════════════╗
         |  ║  ●FLAG_A                 ║
         |  ║   (Butte S)              ║
         |  ║   z=256, x=160           ║
         |  ║   radius 32              ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long east–west canyon; the "lanes" are three parallel north–south approaches to the three buttes, and the canyon walls (the east and west edges) are the safe rotation paths. The three buttes are spaced 96 units apart so each one has its own approach; pushing from one butte to the next along the floor takes you through the contested ground between them.

- **Lane A (South)** — the south approach to FLAG_A (Butte S). Long, clean sightline along the canyon floor, broken by the butte at z=256 itself. A player on Lane A is 32 units from the south wall and has the wall for cover.
- **Lane B (Middle)** — the central approach to FLAG_B (Butte Centre). The contested middle; the round pivots here. The dry riverbed (a 16-unit-wide depression at y=-8) is the lowest ground in the map and the safest rotation path.
- **Lane C (North)** — the north approach to FLAG_C (Butte N). Mirror of Lane A.

## Cut-throughs

- **Canyon floor (E–W)** — the long east–west path at y=0, from x=-160 to x=160. The natural rotation path; a player on the floor can move from one butte to the next without climbing. The dry riverbed in the centre is the lowest part of the floor and the safest rotation path.
- **Butte ramps** — each butte has a single 8-tread ramp on the east side. The ramp is the only way to capture a flag; a player who is on the canyon floor has to climb the ramp to be in the flag's capture radius.
- **Canyon wall gaps** — the canyon walls at x=-160 and x=160 have two 24-unit-wide gaps at z=128 and z=192. The gaps are the alternative rotation path; a player can move from one side of the canyon to the other without using the floor.

## Callouts

- `Butte (N)` — the north butte; FLAG_C.
- `Butte (Centre)` — the central butte; FLAG_B.
- `Butte (S)` — the south butte; FLAG_A.
- `Riverbed` — the central dry riverbed at y=-8, the lowest ground in the map.
- `Canyon Wall (N)` / `Canyon Wall (S)` — the two north–south canyon walls.
- `East Gap` / `West Gap` — the two canyon-wall gaps at z=128 and z=192.
- `Cactus Pair (E)` / `Cactus Pair (W)` — the two pairs of cacti at z=120 and z=200.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 96), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 224) on the west edge, facings aimed at the central butte. The RED spawns are spread between the north and south approaches so RED can pivot between FLAG_C and FLAG_A.
- BLUE: `blue_alpha` (304, 0, 96), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 224) on the east edge, mirror facings.
- The round opens with all three flags NEUTRAL; RED and BLUE both push toward the centre, contest FLAG_B first, and the winning team rolls out to the other two.

## Mode-specific

- **FLAG_C** (Butte N): centre (160, 32, 64), radius 32. Y elevation 32 (butte top).
- **FLAG_B** (Butte Centre): centre (160, 32, 160), radius 32. Y elevation 32 (butte top).
- **FLAG_A** (Butte S): centre (160, 32, 256), radius 32. Y elevation 32 (butte top).
- All flags start NEUTRAL. Score-per-tick: 1 per flag held.
- Rotation: none (the static domination design — flags do not rotate).
- Capture rule: contested (both teams in radius) and empty (neither team in radius) leave the owner unchanged. A flag's owner only changes when exactly one team has at least one body in the radius.

## Bot waypoints

Six waypoints in a closed loop covering the canyon floor and the three butte tops: `wp_0` (160, 32, 64) at FLAG_C, `wp_1` (160, 0, 112) on the floor between butte N and butte Centre, `wp_2` (160, 32, 160) at FLAG_B, `wp_3` (160, 0, 208) on the floor between butte Centre and butte S, `wp_4` (160, 32, 256) at FLAG_A, `wp_5` (160, 0, 160) at the centre of the riverbed. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/sandbar/level.ofm` (Pass 3 — procedurally generated like Crossroads). Sandstone walls (8-band horizontal block lines) plus a 64×64 sand-tone butte-top texture (sparse dark streaks, no horizontal dune bands).
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 6). The Domination mode logic was implemented in Pass 3 (`Match.updateDomination(...)` and `MatchDominationTest`). Pass 6 adds the level model (`engine/src/main/resources/maps/sandbar/level.ofm`, 552 triangles / 1104 vertices / 3 textures, Kenney-textured via the Prototype Kit's `colormap.png`), the spec factory method (`Maps.sandbar()` in `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java`), the registration in `MapLibrary.registerDefaults()`, the Gradle build task (`:tools:buildSandbarMap`, with the optional `-PsandbarAtlas=<colormap.png>` argument for the Kenney-textured build), and a `MapLibraryTest` smoke test (`shouldRegisterSandbar` + `shouldDescribeSandbar`).

Smoke-test command:
```powershell
.\gradlew.bat :engine:run --args="--headless --map=sandbar --fps=60"
```
