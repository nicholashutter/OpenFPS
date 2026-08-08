# hp-foundry — Foundry

**Setting**: Industrial Complex
**Mode**: HARDPOINT
**Sizing**: BO6/BO7 (~320×320 units, 6v6)

## Concept

A heavy-machinery foundry: three large machine halls (the cast-metal shop, the assembly floor, and the cooling room) are the three Hardpoint zones. Each hall is a high-walled, low-ceiling space — the player who holds the hall has the high ground inside the room and the cover of the walls, but the walls also trap them once the opposition has cut the exits. The three halls are connected by a network of gantries at mid-level (y=64), so a team that holds the gantries can rotate between halls without dropping to the floor. The rotation sequence is cast-metal → assembly → cooling, which moves the contested ground south to north over the course of a round.

## Layout

```
        +Y (down in 2D)
         ↑
         |   z=0  ───────────────────────  z=320
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_C              ║
         |  ║   (Cooling Room)        ║
         |  ║   z=40, x=160           ║
         |  ║   radius 48             ║
         |  ╚══════════════════════════╝
         |  ─────  cooling-gantry at y=64 (N-S, x=0)  ─────
         |
         |  ─────  foundry spine at y=64 (E-W, z=160)  ─────
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_B              ║
 LANE B  |  ║   (Assembly Floor)     ║     the contested middle
  (M)    |  ║   z=160, x=160          ║
         |  ║   radius 48             ║
         |  ╚══════════════════════════╝
         |
         |  ─────  casting-gantry at y=64 (E-W, z=80)  ─────
         |
         |  ╔══════════════════════════╗
         |  ║  ●HP_ZONE_A              ║
         |  ║   (Cast-Metal Shop)     ║
         |  ║   z=270, x=160          ║
         |  ║   radius 48             ║
         |  ╚══════════════════════════╝
         |
         +─────────────────────────────────────→  +X (right in 2D)
         x=-160                          x=160
```

## Lanes

The map is **not** a three-lane layout in the COD sense — the two horizontal gantries (the casting-gantry at y=64, z=80 and the foundry spine at z=160) are the "lanes", and they are at mid-height rather than at ground level. The vertical gantry at x=0 connects the two horizontal gantries, so a player can climb the stairway at the cast-metal shop, walk the casting-gantry east, climb to the foundry spine, walk east, drop to the cooling-gantry, and reach the cooling room — all without touching the floor.

- **Foundry spine (y=64, z=160)** — the long east-west gantry through the centre of the map. The contested ground; whichever team holds it can rotate between the cast-metal shop and the cooling room at mid-height.
- **Casting-gantry (y=64, z=80)** — the east-west gantry that connects the cast-metal shop to the foundry spine. Lower than the spine; a player on the casting-gantry can shoot up at anyone on the spine.
- **Cooling-gantry (x=0)** — the north-south gantry at x=0, connecting the cooling room to the foundry spine. The vertical link.

## Cut-throughs

- **Cooling room exits**: two exits at (-15, 0, 40) and (15, 0, 40) leading to the cooling-gantry. A player inside the cooling room can climb onto the gantry through either.
- **Foundry spine exits**: four stairways at the corners of the spine (-100, 0, 160), (100, 0, 160), (-100, 0, 80), (100, 0, 80). A player on the spine can drop to the floor at any of these.
- **Cast-metal shop exits**: two exits at (-15, 0, 270) and (15, 0, 270) leading to the casting-gantry.

## Callouts

- `Cast-Metal Shop` — the south HP zone. The first rotation, the round opens here.
- `Assembly Floor` — the centre HP zone. The second rotation, the contested middle.
- `Cooling Room` — the north HP zone. The third rotation, the round ends here.
- `Foundry Spine` — the central gantry.
- `Casting Gantry` — the gantry that connects the cast-metal shop to the spine.
- `Cooling Gantry` — the gantry that connects the cooling room to the spine.
- `Stairs (NW)`, `Stairs (NE)`, `Stairs (SW)`, `Stairs (SE)` — the four corner stairways.

## Spawns

- 6 spawn points — 3 RED, 3 BLUE.
- RED: `red_alpha` (16, 0, 80), `red_bravo` (16, 0, 160), `red_charlie` (16, 0, 240) on the west edge, facings aimed at the foundry spine.
- BLUE: `blue_alpha` (304, 0, 80), `blue_bravo` (304, 0, 160), `blue_charlie` (304, 0, 240) on the east edge, mirror facings.
- A team that wants the cast-metal shop spawns west and rotates south along the casting-gantry. A team that wants the cooling room spawns west and rotates north along the cooling-gantry. The two spawn-side choices are part of the strategy.

## Mode-specific

- **HP_ZONE_A** (Cast-Metal Shop): centre (160, 0, 270), radius 48.
- **HP_ZONE_B** (Assembly Floor): centre (160, 0, 160), radius 48.
- **HP_ZONE_C** (Cooling Room): centre (160, 0, 40), radius 48.
- Rotation period: 1800 tics (30 s at 60 Hz). Score-per-tick: 1.
- Rotation order: A → B → C → A → B → C ...

## Bot waypoints

Six waypoints in a closed loop covering the floor: `wp_0` (0, 0, 270) inside the cast-metal shop, `wp_1` (-100, 0, 160) at the SW stairway, `wp_2` (-100, 0, 40) at the NW corner, `wp_3` (0, 0, 40) inside the cooling room, `wp_4` (100, 0, 40) at the NE corner, `wp_5` (100, 0, 270) at the SE corner. The loop returns to `wp_0`. 60-tic period. (Bots stay on the floor; a future pass can have them climb stairways, but the floor patrol is the simplest thing that exercises the rotation logic.)

## Textures & Assets

- Level: `engine/src/main/resources/maps/foundry/level.ofm` — Kenney-textured procedural geometry. Floor and wall tiles sampled from the Kenney Prototype Kit's `colormap.png` (CC0; `docs/ASSETS.md` § 3); accent submesh is a hand-authored solid red.
- Weapon: `assets/models/weapon/blaster-b.ofm`.
- Atlas: `assets/gltf/level/Textures/colormap.png` (the staged Kenney Prototype Kit atlas).

## Implementation status

- **FULL** (Pass 5). `Maps.foundry()` is registered in `MapLibrary.registerDefaults()`. The level `.ofm` is generated by the `:tools:buildFoundryMap` task (with the optional `-PfoundryAtlas=<colormap.png>` for the Kenney-textured build) and committed at `engine/src/main/resources/maps/foundry/level.ofm`. The headless smoke test `.\gradlew.bat :engine:run --args="--headless --map=foundry --fps=60"` boots and runs 120 tics without error. 552 triangles, 1104 vertices, 3 textures.
