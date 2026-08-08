# ctf-extraction — Extraction

**Setting**: Urban Warzone
**Mode**: CTF
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A mid-sized urban block split by a long boulevard. Each team's base sits at one end of the boulevard, with the flag in a small structure inside the base. The boulevard is the long sightline — players who take the flag have to run the length of it to score, and the other team can see them coming from across the map. Lane B is the boulevard; lanes A and C run parallel on either side and are the routes a defender uses to flank the carrier. Two bases, one flag each, one capture point per base where the enemy flag must be carried to score.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
 LANE A  |  ┌──────┐                     ┌──────┐
  (N)    |  │ Cover│                     │Cover │
         |  │Wall  │                     │ Wall │
         |  └──────┘                     └──────┘
         |
         |  ─── ●RED_BASE (flag at 32, 32)         ───
         |  ─── ●RED_CAPTURE (capture at 32, 32)    ───
         |       (red's flag is also red's capture point —
         |        the spec places them at the same spot)
         |
 LANE B  |  ═════════════ BOULEVARD ═══════════════
  (M)    |  z=120 ─────  z=200
         |  x=0 ────────  x=320
         |  (the long sightline)
         |       (BLUE flag here at 288, 288 —
         |        blue's flag is also blue's capture point)
         |  ─── ●BLUE_BASE (flag at 288, 288)         ───
         |  ─── ●BLUE_CAPTURE (capture at 288, 288)   ───
         |
 LANE C  |  ┌──────┐                     ┌──────┐
  (S)    |  │Cover │                     │Cover │
         |  │Wall  │                     │ Wall │
         |  └──────┘                     └──────┘
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=0                            x=320
```

## Lanes

- **Lane A (North)** — the north flanking route. Two cover walls at x=64 and x=256, with the red-base entrance between them. A defender can reach Lane B from the north cut-through at z=96.
- **Lane B (Middle)** — the boulevard. The longest open sightline in the map, from x=0 to x=320 at z=160. The flag runs from base to base along this lane; the carrier is visible from the moment they leave their own base until the moment they reach the enemy capture point.
- **Lane C (South)** — the south flanking route. Mirror of Lane A, with cover walls at x=64 and x=256, and the blue-base entrance between them.

## Cut-throughs

- A → B: one cut-through at x=160 in the lane-A boundary wall (z=120).
- B → C: one cut-through at x=160 in the lane-B boundary wall (z=200).
- The two cut-throughs align so a player running down lane B can cut across the map at the centre, dodging the long sightline at the cost of being funneled into a single point.

## Callouts

- `RED Base` — red's flag and capture point at (32, 32, 32). The "extraction" zone for blue.
- `BLUE Base` — blue's flag and capture point at (288, 32, 288). The "extraction" zone for red.
- `North Cut-through` — the A/B cut-through at (160, 32, 120).
- `South Cut-through` — the B/C cut-through at (160, 32, 200).
- `Boulevard Centre` — the open sightline at (160, 32, 160); a long-runner's mid-point.
- `Cover Wall (N)` / `Cover Wall (S)` — the lane-A and lane-C flank cover, x=64 and x=256.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 32), `red_bravo` (16, 0, 64), `red_charlie` (16, 0, 96) on the west edge, facings aimed at RED Base.
- BLUE: `blue_alpha` (304, 0, 224), `blue_bravo` (304, 0, 256), `blue_charlie` (304, 0, 288) on the east edge, facings aimed at BLUE Base.
- Each team's spawns are inside their own base structure, so a spawn camping attempt is hard: the player has to come down the boulevard or through the lane cut-throughs.

## Mode-specific

- **RED Base**: flag at (32, 0, 32), capture at (32, 0, 32), radius 32. (The flag and the capture point sit at the same place — red's capture point IS red's base, the spot a blue carrier must touch to score.)
- **BLUE Base**: flag at (288, 0, 288), capture at (288, 0, 288), radius 32.
- Pickup radius for the enemy flag: 32. Drop-on-death: automatic.
- Score: 1 per capture. Time limit: 10 minutes. Capture limit: 5.

## Bot waypoints

Six waypoints along the boulevard: `wp_0` (16, 0, 32) at red's spawn, `wp_1` (96, 0, 96) on the west boulevard, `wp_2` (160, 0, 160) at boulevard centre, `wp_3` (224, 0, 224) on the east boulevard, `wp_4` (304, 0, 288) at blue's spawn, `wp_5` (32, 0, 256) on the south flank route. The loop returns to `wp_0`. 60-tic period. (Bots in CTF do not pick up flags in Pass 1 — they patrol as defenders and only the local player carries the flag.)

## Textures & Assets

- Level: `engine/src/main/resources/maps/extraction/level.ofm` — a procedurally generated 320×320 model, committed as a deliberate small fixture (`git add -f` overrides the `*.ofm` gitignore rule). **216 triangles, 432 vertices, 3 textures.** Geometry: 1 ground slab + 1 boulevard (320 long, 80 wide, raised 2 units) + 4 low perimeter walls + 2 base platforms (one per team, 64x64 raised 4 units) + 4 cover walls in lanes A and C (flanking cover, 48 tall) + 4 cut-through wall pieces (north and south cut-throughs at z=120 and z=200, each with a gap at x=160) + 2 flagpoles in the accent submesh. Floor and wall textures are sampled from the Kenney Prototype Kit's `colormap.png`; the accent texture is a hand-authored solid red.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: the Kenney Prototype Kit's `colormap.png` (CC0, already in the repo at `assets/gltf/level/Textures/colormap.png`).

## Implementation status

- **FULL** (Pass 2). `Maps.extraction()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildExtractionMap` task and committed at `engine/src/main/resources/maps/extraction/level.ofm`. The CTF mode logic is **fully implemented** in `Match.updateCtf` and tested by `MatchCtfTest` (16 tests) — pickup, drop-on-death, return-on-touch, and capture-on-touch, with both flags returning to their bases on a save or capture. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=extraction --fps=60"` boots and runs 120 tics without error.
