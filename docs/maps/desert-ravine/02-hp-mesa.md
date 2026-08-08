# hp-mesa — Mesa

**Setting**: Desert Ravine
**Mode**: HARDPOINT
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A flat-topped sandstone mesa with a single easy ramp on the south face and a harder switchback stair on the north face. The mesa top is the contested ground; it is open, slightly above the surrounding desert floor (y=32 vs y=0), and the only place where the long sight lines stop being blocked. The two HP zones on the mesa top (C and B) are the high ground; the third zone (A) is a canyon-floor cave to the south, which the round opens on before the rotation pushes the fight onto the mesa. The mesa rim is the chokepoint — a player on the rim can see everything below, but a player below can also see the rim, and a sniper duel from the rim is the round's signature exchange.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_C              ║
         |  ║   (Mesa Top N)           ║
         |  ║   z=64, x=160            ║
         |  ║   radius 48              ║
         |  ╚══════════════════════════╝
         |     ╱ (the N switchback stair,
         |    ╱  z=64..96, x=0..32,
         |   ╱   eight zig-zag treads)
         |  ╱
         |
         |  ╔══════════════════════════╗
LANE B   |  ║  ●HP_ZONE_B              ║
 (M)     |  ║   (Mesa Top S)           ║
         |  ║   z=160, x=160           ║
         |  ║   radius 48              ║
         |  ╚══════════════════════════╝
         |     ╲ (the S ramp,
         |      ╲  z=224..256, x=0..32,
         |       ╲  16% grade)
         |        ╲
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_A              ║
         |  ║   (Cave S)               ║
         |  ║   z=270, x=160           ║
         |  ║   radius 48              ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is built around a single central feature — the mesa — rather than three parallel COD lanes. The "lanes" are the three rotations of the HP zone; the rotation sequence replaces the A/B/C spatial structure.

- **High lane (mesa top)** — the open ground at y=32, from x=80 to x=240 and from z=64 to z=192. The two mesa-top zones (C and B) sit on this lane. Sightlines on the mesa top are clean: a player on the mesa can see the south ramp, the north stair, and the surrounding desert floor in all directions. The mesa rim — a 4-tall sandstone lip at the edge of the top — is the only cover.
- **Mid lane (canyon floor)** — the desert floor at y=0, between the mesa and the periphery. Cover is sparse: a few cacti, the wash channels, and the cave mouth.
- **Low lane (cave)** — the cave interior at y=0, z=240-296. HP_ZONE_A sits here, the first rotation. The cave is a low-roofed (y=24) sandstone chamber, dim and tight.

## Cut-throughs

- **S ramp** — the 16% grade ramp on the south face of the mesa, from the desert floor at (32, 0, 256) to the mesa top at (32, 32, 192). 16 units wide, 8 treads. The contested rotation path; a team that holds the bottom of the ramp can deny the rotation.
- **N switchback** — the eight-tread switchback stair on the north face, from the desert floor at (32, 0, 96) to the mesa top at (32, 32, 96). Eight zig-zag treads at the alternate x=-16 and x=16, 4 units wide each. The slow rotation path; a team that wants to rotate north has to commit to a slow climb.
- **Mesa rim gaps** — the mesa rim has two 8-unit-wide gaps at x=160 on the east and west sides, so a player on the mesa top can drop to the floor without using the ramps. The gaps are the contested close-quarters path; using one is fast but visible from the entire mesa top.

## Callouts

- `Mesa Top (N)` — HP_ZONE_C, the second rotation.
- `Mesa Top (S)` — HP_ZONE_B, the contested middle; the round's pivotal zone.
- `Cave (S)` — HP_ZONE_A, the first rotation. The round opens here.
- `S Ramp` — the easy south face. The contested rotation path.
- `N Switchback` — the slow north face. The alternative rotation path.
- `Mesa Rim (E)` / `Mesa Rim (W)` — the two close-quarters drop points.
- `Cactus Pair (NE)` / `Cactus Pair (SW)` — the two pairs of cacti on the canyon floor, landmarks.
- `Wash (E)` / `Wash (W)` — the two east-west wash channels at z=120 and z=200, low cover.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 256), `red_bravo` (16, 0, 280), `red_charlie` (16, 0, 304) on the south-west edge, facings aimed at the cave mouth at (160, 0, 270). The first rotation (Cave) is on RED's side, so RED spawns near the cave and contests the first zone from the start.
- BLUE: `blue_alpha` (304, 0, 16), `blue_bravo` (304, 0, 40), `blue_charlie` (304, 0, 64) on the north-east edge, facings aimed at the north switchback. BLUE has to climb the switchback to reach the second rotation, so the spawn leans toward the switchback to shorten the climb.
- A team that wants the cave (HP_A) spawns on the south-west; a team that wants the mesa top (HP_C) spawns on the north-east. The spawn choice trades off the first rotation against the third.

## Mode-specific

- **HP_ZONE_A** (Cave S): centre (160, 0, 270), radius 48. Y elevation 0 (cave floor).
- **HP_ZONE_B** (Mesa Top S): centre (160, 32, 160), radius 48. Y elevation 32 (mesa top).
- **HP_ZONE_C** (Mesa Top N): centre (160, 32, 64), radius 48. Y elevation 32 (mesa top).
- Rotation period: 1800 tics (30 s at 60 Hz). Score-per-tick: 1.
- Rotation order: A → B → C → A → B → C ... (the round starts in the cave and ends on the mesa top).

## Bot waypoints

Six waypoints in a closed loop covering the floor and the mesa top: `wp_0` (160, 0, 270) inside the cave, `wp_1` (32, 0, 256) at the bottom of the S ramp, `wp_2` (32, 32, 192) at the top of the S ramp, `wp_3` (160, 32, 160) at Mesa Top S, `wp_4` (32, 32, 96) at the top of the N switchback, `wp_5` (32, 0, 96) at the bottom of the N switchback. The loop returns to `wp_0`. 60-tic period. (Bots stay on the path; a future pass can have them drop through the mesa rim gaps, but the floor-and-ramp patrol is the simplest thing that exercises the rotation logic.)

## Textures & Assets

- Level: `engine/src/main/resources/maps/mesa/level.ofm` — Kenney-textured procedural geometry. Floor and wall tiles sampled from the Kenney Prototype Kit's `colormap.png` (CC0; `docs/ASSETS.md` § 3); accent submesh is a hand-authored solid red. The Kenney pack has no "sand" or "sandstone" tile, so the look matches the prototype-kit's neutral floor and wall — close enough to a desert plateau that the missing swatch is not worth a custom image.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas).

## Implementation status

- **FULL** (Pass 5). `Maps.mesa()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildMesaMap` task (with the optional `-PmesaAtlas=<colormap.png>` for the Kenney-textured build) and committed at `engine/src/main/resources/maps/mesa/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=mesa --fps=60"` boots and runs 120 tics without error. 672 triangles, 1344 vertices, 3 textures.
