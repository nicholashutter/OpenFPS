# Gameplay (P_) — Player, Entities, Map Logic

> P_ is the gameplay / physics layer. Holds everything that *changes during play*.

## What lives here

- `PlayerState` — position (16.16 fixed-point), velocity, angle, pitch, health, armor
- `Entity` — abstract base for all game objects: players, monsters, projectiles, pickups, doors
- `PhysicsWorld` — collision detection, gravity, sliding along walls
- `MapSubsector` / `Sector` — sector data with portal adjacency for movement
- `MapLoader` — parses WAD map lumps (`THINGS`, `LINEDEFS`, `SECTORS`, `SSECTORS`, `NODES`)

## Subsystem layout

```
gameplay/
├── port/
│   └── I_GameplayPort.java   interface — called by core per tic
└── adapter/
    └── NullGameplayPort.java stub
```

## Physics math — what's coming

These are documented in advance so you can verify the formulas before they're coded.
Implementation will reference this README for the source of each formula.

### Collision detection

Two layers, both used together:

1. **BSP traversal** — quick reject. Walk the BSP tree to find the smallest leaf the
   entity is inside. Anything outside that leaf can't collide.
2. **Line-segment / sector** — within the current subsector, the entity's movement
   vector is clipped against each linedef it could cross. This produces "wall sliding"
   (DOOM-style: if a wall is hit, movement is split into its two axis components and
   the un-blocked component is applied).

**Source — DOOM source `p_map.c`, `p_maputl.c`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/p_map.c

### Gravity

```
v.y -= GRAVITY * deltaT
y   += v.y * deltaT
```

GRAVITY is in map-units/sec²; we precompute the per-tic increment.

**Source — Game Programming Gems, "Applying Game-Physics Concepts"**:
https://www.gameprogrammer.com/game-physics.html

### Player movement (DOOM-style)

Per tic, the player has:
- Forward velocity along `angle`
- Strafe velocity perpendicular
- Vertical velocity (for jumping / falling)

Resulting position is rotated by angle and applied to (x, y) with the speed vector.
Then collision: if blocked, the engine tries to slide along the wall by doing the
move twice — once with full X velocity, once with full Y, and the second one uses
the residual from the first. This produces a surprisingly natural feel for arbitrary
angle walls.

**Source — "Player Movement in DOOM" by Jake McArthur**:
http://jake.mcarthur.io/blog/posts/4/player-movement-in-doom-like-games

### Map format (lumps we parse)

- `THINGS` — int16 × 5 per thing: x, y, angle, type, flags
- `LINEDEFS` — int16 × 7 per line: v1, v2, flags, special, tag, sidedir, sidenum
- `SIDEDEFS` — int16 × 6 per side: xoff, yoff, upper, lower, mid, sector
- `VERTEXES` — int16 × 2 per vertex: x, y
- `SEGS` — int16 × 5 per seg: v1, v2, angle, linedef, side
- `SSECTORS` — int16 × 2 per sub-sector: first, count
- `NODES` — int16 × 8 per node: x, y, dx, dy, bb[2][4], children[2]
- `SECTORS` — int16 × 7 per sector: floor, ceil, floorpic, ceilpic, light, special, tag
- `REJECT` / `BLOCKMAP` — used for line-of-sight and quick-reject tests

**Source — DOOM WAD format spec** (Unofficial):
http://doom.wikia.com/wiki/WAD

## Performance constraints

- **Hot path**: physics + map logic runs every tic (30/60/120 Hz) on the game thread.
- **Entity cap**: 4096 active entities per map. Beyond that, evictions happen.
- **No `Math.sqrt`** in collision tests — use squared distance, compare to squared radius.
- **No lambda chains** — see `STYLE.md` section 6.

## Files

- `port/I_GameplayPort.java` — interface
- `adapter/NullGameplayPort.java` — null impl

## TODO (Phase 4)

- `PlayerState` data class
- `Entity` base + concrete types (monster, projectile, pickup, door)
- `PhysicsWorld.moveWithSlide(player, dx, dy)` — collision + slide
- `MapLoader` — read THINGS / LINEDEFS / SECTORS from WAD
- `BspTraverser` — leaf lookup, reused in both gameplay and render
