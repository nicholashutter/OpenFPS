# dom-arctic — Frostline

**Setting**: Arctic Station
**Mode**: DOMINATION
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A long north–south ice road with three flag stations spaced 96 units apart. The road is the central east–west feature; the three flags sit on platforms off the road to the east (FLAG_A at the south, FLAG_B at the centre, FLAG_C at the north). Each platform is a 16×16 raised ice block with a radar mast in the centre; the player who holds a platform controls the radar and the surrounding low ground. The map is the only one of the four shipped where the lanes are E–W (the road runs E–W and the flags are spaced N–S), and the rotation between flags is a sprint along the road. The snow walls at the road's edges break the long sightline the polar flat would otherwise have.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ───── snow wall (N) z=64, y=16 ─────
         |
         |  ╔════╗
         |  ║●C  ║  (FLAG_C, North Platform)
         |  ║mast║
         |  ╚════╝
         |   z=80, x=160
         |   radius 32
         |
         |  ═══════════ ICE ROAD (y=0, z=80..240) ═══════════
         |            (the central east-west feature)
         |
         |  ╔════╗
         |  ║●B  ║  (FLAG_B, Centre Platform)
         |  ║mast║
         |  ╚════╝
         |   z=160, x=160
         |   radius 32
         |
         |  ═══════════ ICE ROAD continues ═══════════
         |
         |  ╔════╗
         |  ║●A  ║  (FLAG_A, South Platform)
         |  ║mast║
         |  ╚════╝
         |   z=240, x=160
         |   radius 32
         |
         |  ───── snow wall (S) z=256, y=16 ─────
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long E–W road; the "lanes" are the road itself, the north snow plain, and the south snow plain. The three flag platforms are spaced 80 units apart along the road (z=80, z=160, z=240), so the rotation between flags is a sprint along the road.

- **Ice Road (y=0, z=80..240)** — the central east–west road. The contested ground; the player who controls the road controls the rotation between flags. The road is 32 units wide.
- **North Snow Plain (y=0, z=0..80)** — the snow plain north of the road. Open ground with sparse snowdrift cover; FLAG_C sits at the edge of this plain.
- **South Snow Plain (y=0, z=240..320)** — mirror of the north plain. FLAG_A sits at the edge of this plain.

## Cut-throughs

- **Road underpasses** — two 32-unit-wide underpasses at x=-100 and x=100 under the road, so a player can cross from the north snow plain to the south snow plain without being shot by a defender on the road.
- **Platform ramps** — each platform has a single 4-tread ramp on the road side (x=160, y=0, z=flag-12 to flag+12). The ramp is the only way to capture a flag; a player on the snow plain has to climb the ramp to be in the flag's capture radius.
- **Snow wall gaps** — the snow walls at z=64 and z=256 have 32-unit-wide gaps at x=-100 and x=100, aligned with the road underpasses. The gaps are the alternative path between the two snow plains.

## Callouts

- `North Platform` — FLAG_C, at (160, 0, 80).
- `Centre Platform` — FLAG_B, at (160, 0, 160).
- `South Platform` — FLAG_A, at (160, 0, 240).
- `Ice Road` — the central east–west road.
- `Snow Wall (N)` / `Snow Wall (S)` — the two long snow walls.
- `North Snow Plain` / `South Snow Plain` — the two flanking snow plains.
- `Radar Mast` — the central feature of each platform.
- `Underpass (W)` / `Underpass (E)` — the two cross-road underpasses.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 96), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 224) on the west edge, facings aimed at the centre of the road. The RED spawns are spread along the road so RED can pivot between FLAG_C (north) and FLAG_A (south) without rotating the spawn side.
- BLUE: `blue_alpha` (304, 0, 96), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 224) on the east edge, mirror facings.
- The round opens with all three flags NEUTRAL; both teams push toward the centre, contest FLAG_B first, and the winning team rolls out to the other two.

## Mode-specific

- **FLAG_C** (North Platform): centre (160, 16, 80), radius 32. Y elevation 16 (platform top).
- **FLAG_B** (Centre Platform): centre (160, 16, 160), radius 32. Y elevation 16 (platform top).
- **FLAG_A** (South Platform): centre (160, 16, 240), radius 32. Y elevation 16 (platform top).
- All flags start NEUTRAL. Score-per-tick: 1 per flag held.
- Rotation: none (the static domination design — flags do not rotate).
- Capture rule: contested (both teams in radius) and empty (neither team in radius) leave the owner unchanged. A flag's owner only changes when exactly one team has at least one body in the radius.

## Bot waypoints

Six waypoints in a closed loop covering the road and the three platforms: `wp_0` (160, 16, 80) at FLAG_C, `wp_1` (96, 0, 120) on the road between FLAG_C and FLAG_B, `wp_2` (160, 16, 160) at FLAG_B, `wp_3` (224, 0, 200) on the road between FLAG_B and FLAG_A, `wp_4` (160, 16, 240) at FLAG_A, `wp_5` (224, 0, 160) on the road east of FLAG_B. The loop returns to `wp_0`. 60-tic period.

## Textures & Assets

- Level: `engine/src/main/resources/maps/frostline/level.ofm` (Pass 4 bonus). Snow-tone floor + sheet-metal walls, similar to the icebridge palette.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 6). The Domination mode logic was implemented in Pass 3 (`Match.updateDomination(...)` and `MatchDominationTest`). Pass 6 adds the level model (`engine/src/main/resources/maps/arctic-dom/level.ofm`, 396 triangles / 792 vertices / 3 textures, Kenney-textured via the Prototype Kit's `colormap.png`), the spec factory method (`Maps.arcticDom()` in `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java`), the registration in `MapLibrary.registerDefaults()`, the Gradle build task (`:tools:buildArcticDomMap`, with the optional `-ParcticDomAtlas=<colormap.png>` argument for the Kenney-textured build), and a `MapLibraryTest` smoke test (`shouldRegisterArcticDom` + `shouldDescribeArcticDom`, with a `displayName` assertion pinning the spec's "Frostline" name).

Smoke-test command:
```powershell
.\gradlew.bat :engine:run --args="--headless --map=arctic-dom --fps=60"
```
