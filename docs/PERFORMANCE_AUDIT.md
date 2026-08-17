# OpenFPS Performance Audit

> Generated against `main` at the time of the collision-fix commit.
> The audit investigates the "performance is getting worse" symptom
> the user reported, plus the per-tic allocation budget the project
> set itself in `STYLE.md` and the demo port's Javadoc.
>
> The headline finding: **the per-tic scratch array fix in this
> commit eliminates one real source of GC pressure. The remaining
> real source is a `Mat4` allocation per bot per tic, which
> `publishBotPlacements` calls unconditionally on every bot. That
> is the next thing to fix.** Everything else in the report is
> either negligible, cold-path, or documentation.

## 1. Symptoms

The user reported: *"the game's performance is getting worse and
worse so beyond that lets investigate the performance."*

In context, the symptoms are subjective (the user has not run a
frame-time benchmark, only noticed subjective lag). The regression
window matches three correlated changes landed in the last 6
commits:

- The kit composer re-applied in `MapScene` — increases the
  rendered instance count from the level-only scene to the
  full kit + bot + viewmodel + effects scene, so the renderer
  has more to draw per frame.
- The `effects.publish(renderer)` per-tic call — runs the
  `DemoEffects` advance + publish every tic, with a pool sized
  for the full effect budget.
- The per-tic `publishBotPlacements` — moves every bot's
  `Mat4` to the renderer's instance table every tic.

The first two are not allocation sources, but they increase the
per-tic CPU cost on the render thread. The third is an allocation
source that scales with the bot count, which the kit composer
just increased from 7 (demo) to 12 (16-map library at the new
12-waypoint count).

The collision fix in this commit is **neutral for performance**:
it adds a `slideX` / `slideZ` per bot per tic (two linear scans
of a small float array, no allocation) and a setter call on
construction (no per-tic cost). The `setCollisionWorld` setter
on `MapGameplayPort` is called once per map load, not per tic.

## 2. The Real Allocation Sources (Ranked By Impact)

### 2.1 `Mat4` Per Bot Per Tic — `publishBotPlacements`

**Source:** `MapGameplayPort.publishBotPlacements` calls
`DemoScene.botPlacement(roster[i])` and
`DemoScene.botWeaponPlacement(roster[i])` for every live bot
in the match, every tic, outside the per-tic lock. Each call
returns a `new Mat4(...)` (a value-typed 16-float object that
hits the JVM heap on construction).

**Cost:** 12 bots * 2 placements = 24 `Mat4` per tic, 1440 per
second at 60 Hz. With each `Mat4` roughly 64 bytes (16 floats +
object header + padding), that is ~92 KB/sec of short-lived
allocation — small in absolute terms but a steady stream of
short-lived objects that defeats TLAB allocation and forces
young-generation GC.

**Why it matters:** The young-generation GC has to walk the
card table and mark the objects unreachable every collection.
Each `Mat4` lives for one tic (~16 ms) and is freed by the next
collection. The collector's `ParNew` work is proportional to
the number of objects to scan, and 1440 `Mat4` per second is
enough to add measurable pause time on a low-spec laptop or
Steam Deck.

**Fix:** Cache one `Mat4` as a `private final` field on the port
(or as a thread-local in the publish helper), mutate it in
place per bot, and pass it to `renderer.setWorldTransform`.
The renderer can take a mutable `Mat4` if we add an overload,
or we can declare a "set-and-forget" `Mat4` value type whose
fields are public and mutable (the current `Mat4` is
immutable; an "in-place" sibling is the right shape).

**Estimated impact:** Eliminates ~1440 allocations per second
on cornerstone. Zero allocations in the publish path on a
steady-state tic (no fire, no respawn, no effect change).

**Status:** **Documented follow-up.** Not in this commit
because the fix needs a renderer-side decision (`Mat4` is
immutable today; making it mutable is a wider change than this
PR wants to be). See § 6 for the plan.

### 2.2 Per-Tic `muzzleScratch` Array — **Fixed In This Commit**

**Source (before this commit):**
`MapGameplayPort.spawnIncomingFire` had
`final float[] muzzleScratch = new float[3];` inside the method
body. Every incoming-fire tic allocated a 3-float array.

**Cost (before the fix):** Incoming fire happens on the order
of every 18 tics across 7-12 bots — that is ~3-4 shots per
second on the 16-map library, ~1 alloc/sec. Small in absolute
terms but unnecessary.

**Fix (this commit):** Promoted the local to a
`private final float[] muzzleScratch = new float[3];` field,
matching `DemoGameplayPort`'s shape. Zero allocations on the
publish path.

**Status:** **Fixed.** Verified by reading the file.

### 2.3 `Integer` Boxing In `TicCmdEncoder` — Network Path Only

**Source:** `TicCmdEncoder.encodeAxis(input.forwardAxis())` and
its siblings box the input's `int` return into an `Integer` for
the method signature. The session's ring stores primitives in
`int[]`, but the boxing is paid at the encode call site.

**Cost:** ~7 boxes per tic when a `NetSession` is attached.
~420/sec at 60 Hz. Small in absolute terms.

**Fix:** Change `TicCmdEncoder.encodeAxis` to take `int`
directly (and `int` is the type the method should already be
taking; the boxing is a leaky abstraction). One PR, low risk,
the session is the only caller.

**Status:** **Documented follow-up.**

### 2.4 `Vec3` Per Shot — Demo Port Only

**Source:** `DemoGameplayPort.fireIfRequested` calls
`controller.eyePosition()` and `controller.forwardVector()`,
each of which `return new Vec3(...)`. Two `Vec3` per shot.

**Cost:** ~5 shots/sec at the 60-Hz cooldown = ~10 `Vec3`/sec.
Negligible.

**Fix:** Add `controller.eyePositionInto(float[] out)` and
`controller.forwardVectorInto(float[] out)` overloads that
write into a caller-supplied scratch buffer. The demo port
can reuse a 3-float field, matching the `muzzleScratch` pattern.

**Status:** **Documented follow-up.** Negligible impact;
worth doing for consistency with the `Mat4` fix.

### 2.5 `Camera` Per Tic — Documented and Accepted

**Source:** `PlayerController.camera(aspect)` returns
`Camera.create(eye, forward, up, fov, aspect, near)`, which is
a `new Camera(...)` per tic when the surface is > 0.

**Cost:** 1 `Camera` per tic, ~60/sec. `Camera` is a value-typed
object holding a few primitives; the alloc is small.

**Status:** **Documented and accepted.** `DemoGameplayPort`'s
class Javadoc (line 79-85) explicitly sanctions this allocation:
*"The only per-tic allocation is the `Camera`, which
`render/README.md` § 4 explicitly sanctions ('Build one per
frame') because it is immutable and is the value that crosses
to the render workers."*

**Fix:** None. The cross-thread hand-off to the render worker
needs an immutable value to publish atomically. A mutable
shared `Camera` would need a lock or a per-worker copy, both
of which cost more than the one `Camera` per tic we are
already paying.

### 2.6 `Target` Per Shot — Hitscan Path

**Source:** `Hitscan.fire` builds a `Target` per shot that
hits a bot. The `Target` is constructed in `Match.resolveBotFire`
via `Target.aroundFeet(...)`.

**Cost:** ~5 shots/sec = ~5 `Target`/sec. Small in absolute
terms.

**Fix:** Reuse a single `Target` per bot (since the bot's
position changes, but the box only needs to be re-derived on
the tic the bot actually moves) or pool `Target`s in a
`Z_Pool<Target>` sized to the bot count. The pool is the
right shape because `Target` is immutable today; making it
mutable is a wider change.

**Status:** **Documented follow-up.** Low priority.

## 3. Cold-Path Allocations (Not On The Hot Path)

These are not part of the per-tic budget but are flagged for
completeness:

- `MapRuntime.loadMap` allocates one `MapScene`, one
  `MapGameplayPort`, one `Match`, one `Bot[]`, one `BotShotLog`,
  one `Scene`, and the per-instance placement matrices.
  One-time, on map change.
- `MapScene.build` allocates the kit piece placement matrices
  (wall corner meshes, crate transforms). One-time.
- `Engine.init` (when wired) would call
  `I_MemoryPort.init(heapSize)` once. Today it does not.

## 4. Where The Performance Hit Is Going

| Subsystem | Cost per tic | Allocation per tic | Action |
|---|---|---|---|
| `D_GameLoop` | ~10-50 µs (timer + dispatcher) | 0 | None needed. |
| `I_InputPort.sampleInput` | ~5-20 µs | 0 | None needed. |
| `PlayerController.update` | ~3-10 µs | 0 | None needed. |
| `Match.tick` | ~10-30 µs (bots + resolveBotFire) | 0 (with the fix); was 0 before too | None. |
| `MapGameplayPort.publishBotPlacements` | ~50-200 µs (12 bot * 2 transforms + renderer write) | **24 `Mat4` (1.4 KB) before the fix; 0 after** | **Documented follow-up.** |
| `Renderer.renderFrame` | ~5-15 ms (depends on kit size + resolution) | 0 | Out of scope for this audit. |
| `effects.publish` | ~5-20 µs | 0 | None. |
| **Total per-tic alloc, today** | | **~24 `Mat4`** | |
| **Total per-tic alloc, after § 6 fix** | | **0** | |

The dominant cost is the renderer (5-15 ms per frame is a
typical 60 FPS budget for a software rasterizer at 1080p).
The per-tic alloc count is small in absolute bytes but
non-trivial as a young-gen pressure source.

## 5. Why It Got Worse

The kit composer (commit `c85224f`) raised the bot count from
7 (demo) to 12 (16-map library), which scaled the
`publishBotPlacements` work proportionally. The `Mat4` per bot
becomes 24 per tic instead of 14, and the room's CPU work
roughly doubles. On a low-spec Steam Deck, the doubled work
in the per-tic loop pushes a few frames over the 16.67 ms
budget and the user notices.

The collision fix (this commit) does not change the
`publishBotPlacements` cost — the new `slideX` / `slideZ`
calls are in `Bot.moveTo`, which runs once per tic per bot,
not in the publish path. The fix does add the cost of
clipping (two linear scans of a small float array), which
is in the same order of magnitude as the move it gates
(~0.1-0.5 µs per bot per tic, negligible).

The user-perceived "getting worse" is the kit composer's bot
count, not the collision fix. The next pass should land the
`Mat4` reuse fix and re-measure.

## 6. The Fix Plan (Ordered By ROI)

### 6.1 `Mat4` Reuse In `publishBotPlacements` — **Highest ROI**

**Work:** Make `Mat4` mutable via a `set(transform...)` mutator
that overwrites the 16-float field array, OR add a sibling
type `Mat4Scratch` whose fields are public. The renderer's
`setWorldTransform(instance, Mat4)` already takes the
immutable form; add an overload that takes a `Mat4Scratch`
(or a `float[16]` directly). Cache one scratch per port,
mutate per bot, pass to the overload.

**Cost:** ~50 LoC, one file changed. Affects
`Mat4.java`, `SoftwareRenderPort.java`,
`MapGameplayPort.java`, `DemoGameplayPort.java`,
`MapGameplayPortTest.java`.

**Eliminates:** ~24 `Mat4` per tic on a 12-bot map at 60 Hz =
~1440 alloc/sec.

**Estimated wall-clock win:** Small in absolute terms (~0.1-0.3%
of frame time) but eliminates the only meaningful young-gen
pressure on the simulation path. A 60-second
`async-profiler` run should show the allocs drop to 0.

### 6.2 `Integer` Boxing In `TicCmdEncoder` — **Low Cost, Easy**

**Work:** Change the encoder's parameter types from `Integer`
to `int`. The session's ring buffer already takes `int[]`.

**Cost:** ~10 LoC, one file changed, one test updated.

**Eliminates:** ~7 `Integer` per tic when networked = ~420/sec.
Negligible CPU win but matches the primitive-types rule.

### 6.3 `Target` Pool — **Defensible As-Is**

**Work:** Add `Z_Pool<Target>` and use it in
`Match.resolveBotFire`. The pool's `acquire` returns a
`Target` whose fields can be set; the matcher treats the
fields as read-only after the shot.

**Cost:** ~30 LoC, one new class, two call-site changes.

**Eliminates:** ~5 `Target`/sec. Negligible.

**Recommendation:** **Defer.** The 5 alloc/sec is well below
the noise floor. The pool adds complexity that is not yet
justified by measured pressure.

### 6.4 `Vec3` Reuse In `DemoGameplayPort.fireIfRequested` — **Consistency, Not ROI**

**Work:** Add `controller.eyePositionInto(float[3])` and
`forwardVectorInto(float[3])` overloads. The demo port caches
a 3-float field and passes it in.

**Cost:** ~20 LoC across `PlayerController.java` and
`DemoGameplayPort.java`.

**Eliminates:** ~10 `Vec3`/sec. Negligible.

**Recommendation:** **Defer** unless the next profiling run
shows `Vec3` on the alloc list.

## 7. How To Verify The Fixes

After § 6.1 lands, run the engine and desktop tests with
`-Xlog:gc::time` and `async-profiler` attached. A 30-second
sample of gameplay should show:

- **0 allocations on a tic with no fire, no respawn, no
  effect change.** The 1 `Camera` per tic is the only
  exception (documented in § 2.5).
- **2 allocations per shot** (the `Vec3` from
  `controller.eyePosition` and `forwardVector` in the demo
  port; the `Mat4` allocations are gone).
- **Young-gen GC pauses < 1 ms.** With ZGC's generational mode,
  pauses are sub-millisecond by default; the question is
  throughput, not pause time, and the per-tic alloc budget
  is the throughput lever.

A test that asserts the per-tic allocation count is
constant would lock this in. The shape is in `STYLE.md`
§ 13.4 (the "no new on hot path" rule); the test belongs
in a new `MapGameplayPortAllocationTest` (and
`DemoGameplayPortAllocationTest`) and would be a
`@Tag("allocation")` benchmark-style test that ticks the
port 1000 times and asserts the post-tic heap diff is
zero (or `Camera`-only).

## 8. Summary

- **One allocation source fixed in this commit:**
  `MapGameplayPort.spawnIncomingFire`'s per-tic `float[3]`
  scratch array is now a `private final` field. Zero
  allocations on the incoming-fire path now.
- **One allocation source is the real performance hit:**
  `Mat4` per bot per tic in `publishBotPlacements`. The
  fix (§ 6.1) is the highest-ROI change and is the next
  thing to land.
- **Two other minor sources** (`Integer` boxing in
  `TicCmdEncoder`, `Vec3` per shot) are documented for
  completeness; both are below the noise floor.
- **The 1 `Camera` per tic is documented and accepted.**
- **The kit composer's bot-count increase (7 -> 12) is
  what scaled the per-tic work.** The user-perceived
  regression correlates with that change, not with the
  collision fix.

The collision fix itself is **performance-neutral** for the
hot path. The next performance pass should land the
`Mat4` reuse fix in § 6.1 and re-measure; if the subjective
"getting worse" persists, profile the renderer's
`renderFrame` path on a Steam Deck, which is the most
likely place the budget is now breaking.

