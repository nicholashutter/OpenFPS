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
├── PlayerController.java     first-person look + movement, produces the Camera
├── port/
│   ├── I_GameplayPort.java   interface — called by core per tic
│   └── I_PlayerInput.java    the four floats PlayerController consumes
└── adapter/
    └── NullGameplayPort.java stub
```

## `PlayerController` — input to camera

The first piece of the first-person work, and pure logic: no rendering, no I/O,
no collision. It holds a feet position, a yaw and a pitch, integrates one tic of
`I_PlayerInput` into them, and hands back an `eyePosition()`, a
`forwardVector()` and a `Camera`.

### Conventions

Right-handed world, **+y up**, matching `render/README.md` § 4.

```
groundForward = ( sin(yaw), 0, cos(yaw) )
groundRight   = groundForward x up = ( -cos(yaw), 0, sin(yaw) )
forward       = ( cos(pitch)*sin(yaw), sin(pitch), cos(pitch)*cos(yaw) )
```

Yaw 0 faces world +z; yaw increases from +z toward +x; positive pitch looks up.

`groundRight` is `forward x up`, **not** `up x forward` — the same operand order
`Camera` pins as normative. The mirror is unusually hard to catch here, because
a mirrored strafe and a mirrored render agree with each other while both being
wrong. Check it physically: with +x east and +y up, +z is south; facing south,
your right hand points west, which is `(-1, 0, 0)`.

Movement uses `groundForward`, never `forward`, so looking up cannot fly.

### Invariants

| Invariant | Where | Why |
|---|---|---|
| pitch clamped to ±89° | `MAX_PITCH_RADIANS` | at exactly ±90° `forward` is parallel to world up, `forward x up` is the zero vector, and `Camera.create` rejects the basis |
| yaw wrapped to `[0, 2π)` | `FULL_TURN_RADIANS` | an unbounded angle loses float precision; near 1e7 rad the spacing between floats is about a radian |
| displacement linear in `deltaSeconds` | `update` | frame-rate independence — invisible until someone plays at a different rate |
| combined axis magnitude clamped to 1 | `update` | a diagonal must not be 41% faster than a straight line, while a half-deflected stick must still walk rather than run — clamped, not normalised, so the controller never double-normalises input the HAL already normalised |

### Determinism — a documented deviation from `PLAN.md` § 4

§ 4 specifies 16.16 fixed-point for simulation state, and player position is
simulation state. `PlayerController` is `float`. This satisfies § 4's *intent*
— bit-identical state across lockstep peers — by a different route, and the
reasoning is on the class Javadoc in full:

- Since **JEP 306** (Java 17) all floating-point expressions are FP-strict IEEE
  754, so `+ - * /` and `sqrt` are bit-reproducible on every conforming JVM and
  CPU. Fixed-point buys nothing the language does not already guarantee.
- The exception is the transcendentals: `Math.sin` / `Math.cos` are permitted
  1–2 ulp and are **not** required to agree between implementations.
  `StrictMath` is fdlibm and is reproducible.

So **every trig call in the update path is `StrictMath`**, and switching one to
`Math` would desync peers silently — sub-micron per step, invisible for
minutes, and impossible to reproduce in a single-process test. A test reads the
compiled class's constant pool and fails if `java/lang/Math` appears in it.

### The input seam

`I_PlayerInput` declares exactly the four floats the controller consumes:
`forwardAxis`, `strafeAxis` (dimensionless, nominally −1..1) and `yawDelta`,
`pitchDelta` (radians, already accumulated for the tic). The HAL's richer
`InputState` — which also carries action flags — adapts onto it in one class.
**Do not grow a second copy of `InputState` here.**

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

- `PlayerController.java` — first-person look and movement (72 tests)
- `port/I_GameplayPort.java` — interface
- `port/I_PlayerInput.java` — the controller's input seam
- `adapter/NullGameplayPort.java` — null impl

## TODO (Phase 4)

- Adapt the HAL `InputState` onto `I_PlayerInput`
- `PhysicsWorld` wraps `PlayerController` and rejects the blocked part of a move
- `PlayerState` data class
- `Entity` base + concrete types (monster, projectile, pickup, door)
- `PhysicsWorld.moveWithSlide(player, dx, dy)` — collision + slide
- `MapLoader` — read THINGS / LINEDEFS / SECTORS from WAD
- `BspTraverser` — leaf lookup, reused in both gameplay and render
