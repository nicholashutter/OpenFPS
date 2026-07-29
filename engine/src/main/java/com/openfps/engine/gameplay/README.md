# Gameplay (P_) — Player, Entities, Map Logic

> P_ is the gameplay / physics layer. Holds everything that *changes during play*.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | 4 — the match layer landed ahead of its phase, as the controller did |
| **Tests** | 321 — counted, not remembered: `gradlew :engine:test --tests 'com.openfps.engine.gameplay.*'` |
| **Registered** | P_ via `GameplaySubsystem` |
| **Verified** | 2026-07-29 |

### The match layer

`Bot`, `BotPattern`, `BotRng`, `BotSkill`, `Match`, `MatchState`, `MatchStatus`,
`MatchSummary` and `MatchMode` turn a room with bodies in it into a game. Four
decisions are worth knowing before touching any of it:

**A bot's position at tic *n* is a pure function of *n*.** Not an integration.
It cannot drift over a long match, a peer that joins late computes the same
answer without replaying history, and — the actual requirement — a player can
watch one for two seconds and know where it will be. These are target
practice; a bot that flanks makes the shooting impossible to evaluate.

**Return fire goes through the same `Hitscan` as the player's own weapon.** A
bot standing in the line of fire genuinely blocks the shot and takes no damage
for it. That was worth more than the emergent cover it produces: it means
seven opponents a second are exercising the same slab test the player's
trigger uses.

**A bot's *shooting* is random, and the randomness is `BotRng` — seeded,
stateless, addressed by `(tic, entity, channel)`.** Read that class before
touching anything that fires. `Math.random()`, a time-seeded `Random`,
`System.nanoTime()` and `ThreadLocalRandom` are all forbidden on the tick path
and would desync two lockstep peers on the first shot, silently, for minutes
before anybody noticed. Stateless matters more than seeded: a seeded generator is
reproducible only if drawn from in the same order the same number of times, so a
single added `if` in the firing path would desync a build against its own
previous version. `BotRngTest` reads the constant pool to enforce all of it.

**Dying is a score, not an ending.** `MatchState.LOST` needs a `deathLimit`,
which defaults to `Match.UNLIMITED_DEATHS`; a death respawns the player after
`RESPAWN_DELAY_TICS` and a round ends when the room is empty. That delay is
counted in **tics** and never in milliseconds — a wall-clock delay would elapse
on different tics on two peers, which is the same class of desync `BotRng` exists
to prevent arriving by a different door.

**Built.** `PlayerController` — first-person look and movement, `StrictMath`
throughout so lockstep peers stay bit-identical, pitch clamped to ±89°, eye at
`EYE_HEIGHT_UNITS` 41 — plus `PlayerInputView`, the one class that adapts the
HAL's `InputState` onto `I_PlayerInput`, and the `I_GameplayPort` /
`I_GameplayPortFactory` pair the core calls per tic. Registration has a nuance
worth knowing before you go looking for the code that runs: `EngineMain` wraps
whatever `I_GameplayPort` its factory hands back in `GameplaySubsystem`, and the
only implementation that plays anything is `demo/DemoGameplayPort`.
`gameplay/adapter/NullGameplayPort` is the fallback, so P_ is registered whether
or not anything is happening.

**Built, and new.** `PhysicsWorld` — the player no longer walks through walls.
It takes a desired ground move and returns the permitted one, so
`PlayerController` stays a pure function of state and arguments; see
[Collision](#physicsworld--collision-that-slides) below for the shape and the
reasoning. `moveWithSlide` landed as the pair `slideX` / `slideZ`, because a
single call would have to return two floats and therefore allocate, and this
runs every tic.

**Not built.** The rest of what Phase 4 names, and none of it exists in any
form: `PlayerState`, `Entity`, `MapSubsector` / `Sector`, `MapLoader`,
`LagCompensator`. The design sections further down this file are specification
written ahead of that code.

**Blocked on.** `MapLoader` *is* blocked: `render/README.md` § 11(b) leaves the
WAD subsystem's remaining role open, and map geometry is the strongest candidate
for it, so do not assume `MapLoader` reads a WAD until that question closes.

**Next step.** A **floor-height query** on `PhysicsWorld`. Collision is
horizontal only today, so solids block at every height and the player cannot
stand on the crates the jump was tuned to clear. That one addition unlocks the
crates, the staircase and the ramp together — see the list of what is and is not
solid below.

## What lives here

Built and tested today:

- `PlayerController` — first-person look and movement in `float`, produces the
  `Camera`; `respawnAt` puts the player back at a spawn placement
- `PhysicsWorld` — the solid geometry a move is clipped against, with sliding
- `PlayerInputView` — presents the HAL's `InputState` as an `I_PlayerInput`
- `Bot` / `BotPattern` — one opponent's route, memory, cooldown and health
- `BotRng` — **the only source of randomness the simulation may have.** Seeded,
  stateless, addressed by `(tic, entity, channel)`
- `BotSkill` — every number that makes the opponents dumb, plus a `MARKSMAN`
  profile that exists so the geometry tests stay about geometry rather than dice
- `Match` / `MatchState` — the rules, the score, the respawn, and `reset()`
- `MatchStatus` / `MatchSummary` — the two immutable copies that cross to the
  render thread: one per frame while a round runs, one when it ends
- `I_GameplayPort` / `I_GameplayPortFactory` — what the core calls per tic, and
  how a launcher builds one after the HAL exists

**Planned — Phase 4. None of the following exist yet**, and the design sections
below them are specification written ahead of the code rather than a description
of it:

- `PlayerState` — position (16.16 fixed-point), velocity, angle, pitch, health, armor
- `Entity` — abstract base for all game objects: players, monsters, projectiles, pickups, doors
- `MapSubsector` / `Sector` — sector data with portal adjacency for movement
- `MapLoader` — parses map lumps (`THINGS`, `LINEDEFS`, `SECTORS`, `SSECTORS`, `NODES`).
  Where those lumps come from is *not* settled — `render/README.md` § 11(b) has
  the WAD subsystem's remaining role open, and map geometry is the strongest
  candidate for it. Do not treat "from a WAD" as decided.

`audio/README.md` and `net/README.md` mark their planned classes the same way;
this file used not to, and read as an inventory of code that does not exist.

## Subsystem layout

```
gameplay/
├── PlayerController.java          first-person look + movement, produces the Camera
├── PhysicsWorld.java              solid boxes; clips a desired move, slides
├── PlayerInputView.java           adapts the HAL InputState onto I_PlayerInput
├── port/
│   ├── I_GameplayPort.java        interface — called by core per tic
│   ├── I_GameplayPortFactory.java builds the port once the HAL is initialised
│   └── I_PlayerInput.java         the four floats PlayerController consumes
└── adapter/
    └── NullGameplayPort.java      stub
```

### The only live implementation

`NullGameplayPort` is a stub, so the one
`I_GameplayPort` that actually plays anything is **`demo/DemoGameplayPort.java`**
— it latches input, drives a `PlayerController` and aims the software renderer's
camera, once per tic, under a lock. It is also the only place `PlayerController`
is driven outside its own tests, which makes it the first thing to read when a
change here needs to be seen rather than asserted. See
`engine/src/main/java/com/openfps/engine/demo/README.md`.

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
`InputState` — which also carries action flags — adapts onto it in
`PlayerInputView`, and that one class is the whole adapter.

Two things about it are deliberate and easy to undo by accident:

- **It is not `InputState implements I_PlayerInput`.** The four accessors match
  name for name, so the inheritance looks free. It is not: `InputState` lives in
  `hal.port`, the contract every platform adapter compiles against, and making
  the HAL implement a gameplay interface inverts the dependency `PLAN.md` § 2
  draws (`hal` depends on `common` and nothing else). Gameplay knows about the
  HAL; never the reverse.
- **It is mutable on purpose.** `InputState` is immutable and a fresh one is
  latched every tic, so a per-tic wrapper would allocate forever in the
  simulation path. One view is created at startup and re-pointed with `wrap`.

The action flags (`fire`, `jump`, `sprint`) are not forwarded — the controller
has no use for them. Whatever consumes them later should read the `InputState`
directly rather than widening this seam.
**Do not grow a second copy of `InputState` here.**

## `PhysicsWorld` — collision that slides

The class `PlayerController`'s Javadoc has promised since it was written: it
"wraps this later and rejects the parts of a move that hit a wall". It holds
axis-aligned boxes and nothing else — no player, no clock, no state once built —
so it stays a pure function and the controller in front of it stays
deterministic.

### The body

An **upright axis-aligned box, half-width 16 world units** (`32` across, `56`
tall). That is `Constants.PLAYER_RADIUS / MAP_SCALE`, not a new number: the room
was already dimensioned against it. `DemoScene.KIT_WORLD_SCALE` justifies the
64-unit grid partly by "a 32-unit-diameter player in a 64-unit doorway has a
radius of clearance either side", and `BOT_ROUTE_CENTRES` keeps every patrol
inside the wall by the same 16.

A box rather than a cylinder because every solid in the room is itself
axis-aligned, which makes the test four comparisons with no square root — DOOM's
32×32×56 player box, which also called its half-width a radius. The two differ
only within 4.7 units of a corner. The footprint does not rotate: movement is
yaw-only, and a square hull that spun with the view would make clearance depend
on which way the player was looking.

### The two calls, and why the order is fixed

```java
newX = world.slideX(x, z, deltaX);
newZ = world.slideZ(newX, z, deltaZ);   // note: the CLIPPED x
```

Two calls rather than one because a single `moveWithSlide` would have to return
two floats, and this runs up to 120 times a second.

- **Per-axis clipping is the sliding.** The blocked component is lost, the free
  one survives, so a diagonal into a wall keeps travelling *along* it. A solver
  that zeroes the whole move turns every wall into glue, and is the single
  biggest difference between "has collision" and "feels right".
- **`slideZ` gets the clipped `x`.** That is what makes an inside corner hold.
  Measured from the x the player *asked* for rather than the one they reached,
  a corner leaks diagonally.
- **x-then-z, always.** Not "the larger component first", not "whichever is
  blocked less". Two peers whose floats differ by one ulp would take different
  branches and then disagree about where a player is standing.

Blocked moves are clipped **to the exact contact plane**, not discarded:
rejecting the step outright leaves the player up to one step short of the wall —
8.5 units at 30 Hz — which is invisible, tic-rate dependent and jittery.

A body **already inside** a solid is never resolved, only the crossing of a face
from outside is. An embedded body walks out rather than being teleported to a
face or pinned forever.

### What is solid in the demo room

`DemoScene.kitRoomPhysics()` owns the answer, because `DemoScene` is the only
thing that knows where the room is.

| | |
|---|---|
| **Solid** | the four perimeter walls, the two doorway jambs, four columns, seven floor-standing crates — 16 boxes |
| **Open** | the doorway: a 64-unit gap between two south-wall slabs, which a 32-unit body clears with 16 units either side |
| **Not solid** | the staircase and the ramp |

Each wall is **one slab spanning its whole side**, not the ten tile boxes the
renderer draws. A row of abutting boxes has nine internal edges, and a body
sliding along it that drifts an ulp inside the surface catches on every one.

The hull stops at the wall's **visible face at 313.6**, not the centre line at
320 where `addWalls` places the instance, so the furthest a player may stand is
**±297.6** with their shoulder against the plaster.

The staircase and ramp are left permeable deliberately: collision is horizontal
only, so both would need full-height boxes, and an invisible wall in front of a
ramp you can plainly see reads as a bug where walking through it merely reads as
unfinished. The staircase also sits at `z = 224` on the way to the door.

### Cost and determinism

Two calls per tic over a flat `float[]` of 16 boxes. **No allocation, no
`java.lang.Math`, no `sqrt`, no clock, no randomness** — only comparisons and
sums, which JEP 306 makes bit-reproducible, as `net/README.md`'s lockstep model
requires. `PhysicsWorldTest` reads the constant pool to enforce it, the same
guard `PlayerController` carries.

### Driving it without a keyboard

Collision is the one feature nothing automated could reach: everything else the
screenshot harness photographs is true of a stationary player. `GdxInputPort`
holds a movement axis for the first N tics when asked, which is what makes the
proof takeable:

```
gradlew :desktop:run "--args=--start-in-game" -Dopenfps.autoWalkTics=300
        -Dopenfps.autoWalkStrafe=-0.0654
        -Dopenfps.screenshot=C:\tmp\wall.png -Dopenfps.screenshotExit=true
```

The position on the `Demo gameplay stopped after N tics at …` line is the
measurement. Walking forward from the spawn: **`z = 1087.998`** before this
landed — 774 units outside the room — and **`z = 297.6`** after, on the contact
plane to the last digit.

## Physics math — what's coming

These are documented in advance so you can verify the formulas before they're coded.
Implementation will reference this README for the source of each formula.

### Collision detection

**What shipped is the second layer only, against boxes rather than linedefs** —
see [`PhysicsWorld`](#physicsworld--collision-that-slides) above. The BSP
quick-reject below is still specification: a 16-box room is a linear scan of 64
floats, so a tree to avoid it would cost more than it saved. It becomes worth
building when a real map's linedef count does.

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
- `PhysicsWorld.java` — solid boxes, clipping and sliding (28 tests)
- `PlayerInputView.java` — the HAL `InputState` adapter (5 tests)
- `port/I_GameplayPort.java` — interface, called by core per tic
- `port/I_GameplayPortFactory.java` — deferred construction, mirroring `I_RenderPortFactory`
- `port/I_PlayerInput.java` — the controller's input seam
- `adapter/NullGameplayPort.java` — null impl

**105 tests across those four files**, of 257 in the package once the match
layer is counted. Run with `.\gradlew.bat test`.

## TODO (Phase 4)

- ~~`PhysicsWorld` wraps `PlayerController` and rejects the blocked part of a
  move~~ — **done**, as `slideX` / `slideZ`
- `PhysicsWorld.floorHeightAt(x, z)` — the missing half. Collision is
  horizontal only, so nothing can be stood on: the crates, the staircase and the
  ramp are all waiting on this one method, and the jump was tuned to clear a
  crate it currently cannot land on
- Clip the **bots** against the same world. They walk fixed routes that happen
  to stay inside the wall, which is an arithmetic coincidence maintained by a
  test rather than a physical fact
- `PlayerState` data class
- `Entity` base + concrete types (monster, projectile, pickup, door)
- `MapLoader` — read THINGS / LINEDEFS / SECTORS, once `render/README.md`
  § 11(b) settles what the map container is
- `BspTraverser` — leaf lookup, reused in both gameplay and render
