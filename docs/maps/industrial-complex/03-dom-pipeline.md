# dom-pipeline — Pipeline

**Setting**: Industrial Complex
**Mode**: DOMINATION
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A pipeline pumping station: three long pipelines run east–west across the map, each with a control valve at its centre. The three flags are the control valves — capture one and you control the flow through that pipeline. The map plays as a long east–west corridor: RED spawns on the west and pushes east, BLUE spawns on the east and pushes west, and the contested ground is the centre. The mid-level catwalks (y=64) run alongside each pipeline so a player who has climbed up can see across the whole map. The catwalk level is the high ground; the floor is the contested ground; the three flag positions are the rewards.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ───── catwalk at y=64 (N-S, x=-80) ─────
         |  ───── catwalk at y=64 (N-S, x=0)   ─────
         |  ───── catwalk at y=64 (N-S, x=80)  ─────
         |
         |  ═════════════════════════════════════
         |  ║  ●FLAG_C (Pipeline North)         ║   z=64, x=160
         |  ║   radius 32                      ║
         |  ═════════════════════════════════════
         |
         |  ═════════════════════════════════════
 LANE B  |  ║  ●FLAG_B (Pipeline Centre)        ║   z=160, x=160
  (M)    |  ║   radius 32                      ║
         |  ╚════════════════════════════════════
         |
         |  ═════════════════════════════════════
         |  ║  ●FLAG_A (Pipeline South)         ║   z=256, x=160
         |  ║   radius 32                      ║
         |  ═════════════════════════════════════
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long east–west corridor; the "lanes" are three parallel east–west pipelines at z=64, z=160, and z=256. Each pipeline has a control valve (the flag) at its centre. Three catwalks at y=64 run north–south alongside the pipelines so a player can climb up and shoot across the floor from above.

- **Pipeline North (FLAG_C)** — the north pipeline. The first flag to flip; RED reaches it before BLUE because the spawns are weighted toward the south.
- **Pipeline Centre (FLAG_B)** — the contested centre. The flag that decides the round; whoever holds it scores from a flag the enemy cannot easily reach.
- **Pipeline South (FLAG_A)** — the south pipeline. The first flag RED holds from the spawn; the round opens with it.

## Cut-throughs

- **E–W underpasses**: each pipeline has a 64-unit-wide underpass at x=-100 and x=100 so a player can cross the pipeline without being shot by a defender on the catwalk. The underpasses are the only safe way to rotate; crossing at the valve (x=160) is the contested direct path.
- **Catwalk connections**: the three catwalks at y=64 connect to the perimeter walls at the corners via short stairways. A player on any catwalk can reach any other catwalk without dropping to the floor.

## Callouts

- `Pipeline North` — the north pipeline, FLAG_C.
- `Pipeline Centre` — the centre pipeline, FLAG_B.
- `Pipeline South` — the south pipeline, FLAG_A.
- `North Catwalk` — the y=64 catwalk alongside FLAG_C.
- `Centre Catwalk` — the y=64 catwalk alongside FLAG_B.
- `South Catwalk` — the y=64 catwalk alongside FLAG_A.
- `West Underpass`, `East Underpass` — the two cross-pipeline underpasses.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 96), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 224) on the west edge, facings aimed at Pipeline Centre.
- BLUE: `blue_alpha` (304, 0, 96), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 224) on the east edge, mirror facings.
- The RED spawns lean toward the south pipeline (FLAG_A) so RED starts holding at least one flag; the BLUE spawns lean toward the north pipeline (FLAG_C). The round opens with one flag per side, and the centre (FLAG_B) is the contested ground.

## Mode-specific

- **FLAG_C (Pipeline North)**: centre (160, 0, 64), radius 32.
- **FLAG_B (Pipeline Centre)**: centre (160, 0, 160), radius 32.
- **FLAG_A (Pipeline South)**: centre (160, 0, 256), radius 32.
- All flags start NEUTRAL. Score-per-tick: 1 per flag held.
- Rotation: none (the static domination design — flags do not rotate).

## Bot waypoints

Six waypoints in a closed loop covering the floor at the three flag positions: `wp_0` (160, 0, 64) at FLAG_C, `wp_1` (240, 0, 64) at the east end of the north pipeline, `wp_2` (240, 0, 160) east of FLAG_B, `wp_3` (160, 0, 256) at FLAG_A, `wp_4` (80, 0, 256) west of FLAG_A, `wp_5` (80, 0, 160) west of FLAG_B. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `assets/maps/pipeline/level.ofm` (Pass 3).
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 6). The Domination mode logic was implemented in Pass 3 (`Match.updateDomination(...)` and `MatchDominationTest`). Pass 6 adds the level model (`engine/src/main/resources/maps/pipeline/level.ofm`, 444 triangles / 888 vertices / 3 textures, Kenney-textured via the Prototype Kit's `colormap.png`), the spec factory method (`Maps.pipeline()` in `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java`), the registration in `MapLibrary.registerDefaults()`, the Gradle build task (`:tools:buildPipelineMap`, with the optional `-PpipelineAtlas=<colormap.png>` argument for the Kenney-textured build), and a `MapLibraryTest` smoke test (`shouldRegisterPipeline` + `shouldDescribePipeline`).

Smoke-test command:
```powershell
.\gradlew.bat :engine:run --args="--headless --map=pipeline --fps=60"
```
