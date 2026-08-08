# ctf-arctic — Coldfront

**Setting**: Arctic Station
**Mode**: CTF
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A small polar-research base split across two sides of a frozen river. The RED base sits on the west bank, the BLUE base sits on the east bank, and the frozen river between them is the contested ground. Each base is a small compound: a main hut (the flag's home), a watchtower (the chokepoint that watches the river), and a service shed (the cut-through that lets a defender rotate from the hut to the tower without crossing the river). The flag sits on a small pedestal inside the main hut; the capture point sits on the doorstep of the main hut, so the carrier's last step is to walk out the front door. The frozen river is wide (96 units) and unbroken, so the carrier's run from base to base is roughly 256 units long, with the watchtower of the enemy base visible the entire way.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●RED_BASE              ║
         |  ║  (West Compound)         ║
         |  ║  ●Watchtower (W)         ║
         |  ║  ●Main Hut — flag at (32, 32) ║
         |  ║  ●Service Shed           ║
         |  ╚══════════════════════════╝
         |
         |  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
LANE B   |  ░░░ FROZEN RIVER (y=-8) ░░░
 (M)     |  ░░░ 96 units wide         ░░░
         |  ░░░ the contested crossing ░░░
         |  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
         |
         |  ╔══════════════════════════╗
         |  ║  ●BLUE_BASE             ║
         |  ║  (East Compound)         ║
         |  ║  ●Service Shed           ║
         |  ║  ●Main Hut — flag at (288, 288) ║
         |  ║  ●Watchtower (E)         ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is a long E–W corridor; the "lanes" are the two base compounds and the central frozen river.

- **West Compound (RED)** — the west bank at x=-160..-32, z=112..208. Three structures: the Main Hut (32×32 at -96, 160, with the flag at -128, 160), the Watchtower (16×16×48 at -32, 160, on the river side), and the Service Shed (24×24 at -64, 120).
- **Frozen River (y=-8, x=-32..32, z=64..256)** — the central contested ground. 96 units wide (E–W), 192 units long (N–S). No cover; a carrier on the river is visible from both watchtowers the entire way.
- **East Compound (BLUE)** — mirror of the West Compound, on the east side at x=32..160. The Main Hut has the flag at (128, 160, the watchtower is on the west side at (32, 160), and the Service Shed is at (64, 120).

## Cut-throughs

- **Service shed passages** — each service shed has a 16-unit-wide internal passage connecting the Main Hut to the Watchtower, so a defender can rotate between the two without crossing the river. The passage is the natural cut-through; a defender who has spawned in the shed can choose to defend the river (via the watchtower) or the flag (via the main hut) without going outside.
- **Watchtower roofs** — the watchtowers are 48-tall, with a 16×16 platform on top. A defender on the tower roof can see across the entire river and into the enemy compound; the tower roof is reached by an internal stairway (Pass 5+ design; Pass 4 has the tower as a solid block with a 4-tread external stair).
- **Main hut doorways** — each main hut has a 16-unit-wide front doorway on the river side (x=±96) and a 16-unit-wide back doorway on the wall side. A carrier exiting the hut is in the open for the first 16 units, but the doorway is the only way in or out.

## Callouts

- `Red Base` / `West Compound` — RED's flag at (-128, 160) and capture at (-128, 160), both in the Main Hut.
- `Blue Base` / `East Compound` — BLUE's flag at (128, 160) and capture at (128, 160), both in the Main Hut.
- `Red Watchtower` / `Blue Watchtower` — the two watchtowers on the river side of each compound.
- `Red Service Shed` / `Blue Service Shed` — the two service sheds on the back side of each compound.
- `Frozen River` — the central contested crossing, 96 units wide.
- `River (N)` / `River (S)` — the two ends of the river, where the watchtower fire is weakest.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (-144, 0, 128), `red_bravo` (-144, 0, 160), `red_charlie` (-144, 0, 192) on the west edge, facings aimed at the Red Watchtower. The RED spawns are outside the compound, with the West Compound's west wall providing immediate cover.
- BLUE: `blue_alpha` (144, 0, 128), `blue_bravo` (144, 0, 160), `blue_charlie` (144, 0, 192) on the east edge, mirror facings.
- Each team's spawns are outside the compound on the wall side, so a defender who has spawned can either run to the flag (via the Main Hut), climb the watchtower, or wait at the front doorway for the carrier to come home.

## Mode-specific

- **RED Base**: flag at (-128, 0, 160), capture at (-128, 0, 160), radius 32. (The flag and the capture point sit at the same place — red's capture point IS red's base, the spot a blue carrier must touch to score.)
- **BLUE Base**: flag at (128, 0, 160), capture at (128, 0, 160), radius 32.
- Pickup radius for the enemy flag: 32. Drop-on-death: automatic (returns to base instantly).
- Return on touch: touching your own flag while carrying the enemy flag returns both to their bases (a "save").
- Score: 1 per capture. Time limit: 10 minutes. Capture limit: 5.
- Bots patrol as defenders and never pick up or carry the flag.

## Bot waypoints

Six waypoints in a closed loop covering both compounds and the river edges: `wp_0` (-128, 0, 160) at the RED Main Hut, `wp_1` (-32, 0, 160) at the Red Watchtower, `wp_2` (-32, 0, 100) at the north end of the river on the RED side, `wp_3` (32, 0, 100) at the north end of the river on the BLUE side, `wp_4` (32, 0, 160) at the Blue Watchtower, `wp_5` (128, 0, 160) at the BLUE Main Hut. The loop returns to `wp_0`. 60-tic period. (Bots in CTF do not pick up flags in Pass 4 — they patrol as defenders and only the local player carries the flag.)

## Textures & Assets

- Level: `engine/src/main/resources/maps/coldfront/level.ofm` (Pass 5+). Snow-tone floor + sheet-metal walls, similar to the icebridge palette.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: none.

## Implementation status

- **FULL** (Pass 7). `Maps.coldfront()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildColdfrontMap` task and committed at `engine/src/main/resources/maps/coldfront/level.ofm`. The CTF mode logic is **fully implemented** in `Match.updateCtf` and tested by `MatchCtfTest` (16 tests) — pickup, drop-on-death, return-on-touch, and capture-on-touch, with both flags returning to their bases on a save or capture. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=coldfront --fps=60"` boots and runs 120 tics without error. 132 triangles, 264 vertices, 2 textures.
