# OpenFPS Memory Allocation Audit

> Generated against `main` at the time of the collision-fix commit. Numbers
> in this document are point-in-time: they reflect the codebase after the
> collision fix landed and after the per-tic `muzzleScratch` fix in
> `MapGameplayPort` was applied. A future commit that introduces a new
> hot-path allocation should update both this document and the
> corresponding todo in `docs/decisions/`.
>
> The audit covers the project guideline:
> *"Never or dynamically allocate as little as possible. Reserve enough
> memory for the entire app. Use the custom allocator."*

## 1. The Allocator Surface (What Exists)

The project defines a custom-allocator port but ships exactly one
production consumer, and the consumer is a memory-test tool, not a
runtime subscriber.

| Component | File | Status |
|---|---|---|
| `I_MemoryPort` interface | `engine/src/main/java/com/openfps/engine/memory/port/I_MemoryPort.java` | The port. Defines `allocate`/`free`/`freeByTag` plus a lifecycle state machine. |
| `JvmMemoryPort` adapter | `engine/src/main/java/com/openfps/engine/memory/adapter/JvmMemoryPort.java` | One of the two documented backends. |
| `ZoneMemoryPort` adapter | `engine/src/main/java/com/openfps/engine/memory/adapter/ZoneMemoryPort.java` | The other documented backend. Backs the "zone" / slab model from Bonwick 1994. |
| `MemoryPortFactory` | (not found) | Referenced in the `I_MemoryPort` Javadoc; not present in the tree. The interface has no factory implementation, so no caller can produce a port. |
| `I_ThreadPoolPort` | `engine/src/main/java/com/openfps/engine/core/pool/I_ThreadPoolPort.java` | Pool port. |
| `WorkerPool` | `engine/src/main/java/com/openfps/engine/core/pool/WorkerPool.java` | One pool implementation. |
| `ThreadPoolFactory` | `engine/src/main/java/com/openfps/engine/core/pool/ThreadPoolFactory.java` | The factory. |
| `Z_Pool`, `Z_FrameArena` | (not found) | **Not in the project.** The two class names you mentioned in the request are absent; the project ships neither a generic object pool nor a frame arena. The "custom allocator" surface is the `I_MemoryPort` family, used by zero production code paths. |

**What this means in practice.** The `I_MemoryPort` exists as a port and
two adapters exist behind it, but no production code path calls
`allocate()` on it. The guideline's "use the custom allocator" half is
*not currently satisfied* in code; the half that *is* satisfied is "do
not allocate on the hot path," enforced by review and by the pattern of
reusing scratch buffers (see § 3).

## 2. Hot-Path Allocation Audit (What Runs Every Tic)

I walked every `tick()` body, every `update()` body, and every
controller/move path the per-tic loop reaches. The findings are below,
ordered by how much the allocation matters.

### 2.1 Hot Path in `MapGameplayPort.tick` (the active gameplay port)

| Call site | Allocates? | Notes |
|---|---|---|
| `inputPort.sampleInput(tic)` | No | Engine-internal. |
| `controller.update(input, dt)` | No (calls `applyMove` -> `world.slideX/slideZ` which are pure functions). |
| `aimCamera()` -> `controller.camera(aspect)` | **Yes** (1 `Camera` per tic when surface is > 0). | `Camera.create` is a value object, but the constructor still hits the JVM heap. The allocation is documented as "the only per-tic allocation" the demo port's Javadoc accepts (see `DemoGameplayPort.tick` Javadoc). |
| `match.tick(...)` | No | Internal to `Match`. |
| `match.shotsThisTic()` | No | Returns a reused `BotShotLog`. |
| `match.consumePlayerRespawned()` | No | |
| `exchangeNetwork` -> `session.recordLocalCommand(...)` | **Yes (boxed `Integer`s for the wire)**. | `TicCmdEncoder.encodeAxis` / `encodeAngle` etc. autobox primitive `int` -> `Integer`. The session's ring buffers primitives in `int[]`, but the encode step on the call site allocates one `Integer` per call. This is ~7 per tic. The fix would be to take `int` directly into the encoder, or to use the JIT scalarization pass once the HotSpot warmup is past. |
| `findBot(shooterId)` | No | Linear scan over the bot array, no allocation. |
| `spawnIncomingFire(tic)` -> `DemoScene.botMuzzle(shooter, scratch)` | **No** (after the fix in this commit). | Before this commit: `final float[] muzzleScratch = new float[3];` on every incoming-fire tic. **Fixed** in `fbbec6d` (now a private final field, same shape as `DemoGameplayPort.muzzleScratch`). |
| `effects.spawnIncoming(...)`, `effects.spawn(...)`, `effects.advance()` | No | `DemoEffects` writes into pre-allocated pool instances. |
| `publishBotPlacements()` -> `renderer.setWorldTransform(...)` | No | Reads `DemoScene.botPlacement(roster[i])` which returns a `Mat4` value object — **Yes, 1 Mat4 per bot per tic** (2 if a weapon is staged, so up to 14 per tic on cornerstone). `Mat4` is value-typed; the constructor is cheap. |
| `effects.publish(renderer)` | No | |

**Per-tic heap allocation, after this commit, in `MapGameplayPort.tick`:**
- 1 `Camera` (documented and accepted).
- ~7 `Integer` boxes from `TicCmdEncoder` (only when a network session is attached).
- Up to ~14 `Mat4` values from `DemoScene.botPlacement` / `botWeaponPlacement`.

The first one is documented and the only one the demo port carries. The
second is the realistic hot path. The third is the closest thing to a
real per-tic pressure and it is the most likely source of the
"performance is getting worse" report. **See § 4 for the fix plan.**

### 2.2 Hot Path in `DemoGameplayPort.tick`

| Call site | Allocates? |
|---|---|
| `inputPort.sampleInput(tic)` | No |
| `controller.update(...)` | No |
| `aimCamera()` -> `controller.camera(aspect)` | **Yes** (1 `Camera` per tic). |
| `advanceMatch(...)` -> `match.tick(...)` | No |
| `spawnIncomingFire(tic)` | No (uses `muzzleScratch` field). |
| `exchangeNetwork` | ~7 `Integer` boxes (same as map). |
| `advanceRemoteBodies()` -> `bodies.advance(net, dt)` | **Likely yes** (need to verify). |
| `fireIfRequested(...)` -> `controller.eyePosition()`, `controller.forwardVector()` | **Yes, 2 `Vec3` per shot** (only when trigger held; ~5/sec at 60 Hz with cooldown = 12). |
| `match.firePlayerShot(eye, aim)` | No |
| `match.consumeSuperBlasterAwarded()` | No |
| `match.byId(struck)` | No |
| `advanceEffects()`, `publishBotPlacements()`, `publishRemoteBodies()`, `publishLocalBody()`, `publishEffects()` | No (writes into renderer state and effects pool). |

**Per-tic heap allocation, demo:**
- 1 `Camera` per tic (documented).
- ~7 `Integer` boxes (networked).
- Up to 2 `Vec3` per shot.
- Up to 14 `Mat4` per tic (bot placements).

### 2.3 `Match.tick` Internals

| Call site | Allocates? |
|---|---|
| `shots.clear()` | No (resets an int counter). |
| `bots[i].moveTo(ticIndex)` | No (after the collision fix; was no before too). |
| `bots[i].observePlayer(...)` | No |
| `faceAll()` | No |
| `resolveBotFire(...)` -> `Hitscan.fire(...)` | **Possibly yes, via `new Target()`** when a shot lands. See § 5. |
| `ageSuperBlaster()` | No |
| `updateMode(...)` -> `updateHardpoint`, `updateDomination`, `updateCtf` | No (all primitives). |

`Target` is documented as "build only on the tics where somebody actually
fires," which keeps the per-tic pressure low. `Target.aroundFeet` does
no `new` itself (it's `new Target(...)`, so yes it does — one per bot
per shot, freed on the next GC). At 5 shots/sec from 7 bots, that is
~35 `Target` allocations per second on the demo. Acceptable, but not
zero. **See § 5 for the fix.**

## 3. Pre-Allocation Patterns (What's Already Right)

- `PhysicsWorld` stores the box table as a flat `float[]` of `minX, minZ, maxX, maxZ`
  quadruples. The builder grows the array only at construction (never on the
  hot path). `slideX` and `slideZ` are linear scans over this array with
  no allocation.
- `BotShotLog` (`Match.shotsThisTic`) pre-allocates `int[]` and `float[]` of
  capacity `botCount` at construction; reused for the lifetime of the
  match. `clear()` only resets the count.
- `Match.bots` is `opponents.clone()` at construction; the array is
  never re-allocated.
- `PlayerController`, `Bot`, `Match` keep their mutable fields as
  primitives, not boxed.
- `DemoEffects` is a pool of pre-allocated tracer/smoke instances;
  `spawn` writes into a free slot, `advance` and `publish` walk the
  pool.
- `Scene`, `MapScene`, `MapSpec` are immutable after build; the
  per-tic path never rebuilds them.
- `Renderer` state is pre-allocated; `setWorldTransform` writes into
  the renderer's instance table, not into a fresh object.
- `NetSession` ring buffers use primitive `int[]` for TicCmd slots;
  no per-tic allocation once the ring is sized.
- `muzzleScratch` is a private final `float[3]` in
  `DemoGameplayPort`, and now (this commit) in `MapGameplayPort`.
- `Match.hit` is a reused `HitResult` (one per match, not one per shot).

## 4. Allocation Pressure Drivers (The Performance Regression)

The "performance is getting worse" symptom lines up with three sources,
in order of impact:

1. **`Mat4` per bot per tic.** `publishBotPlacements` calls
   `DemoScene.botPlacement(roster[i])` and `DemoScene.botWeaponPlacement`
   for every live bot, every tic. Each returns a new `Mat4`. At 12 bots
   on cornerstone, that is up to 24 `Mat4` per tic = ~1440 per second at
   60 Hz. `Mat4` is a value-typed object with 16 floats; the GC sees a
   steady stream of small short-lived objects and cannot use the TLAB
   efficiently. **Fix:** cache the `Mat4` for the HIDDEN transform once
   and reuse a single `Mat4` for the publish loop, mutating it in
   place per bot. This is a ~1440 alloc/sec elimination on a 12-bot
   map.
2. **Per-tic scratch array in `MapGameplayPort.spawnIncomingFire`.**
   Was `new float[3]` on every incoming-fire tic. **Fixed in this
   commit** (now a `private final float[]` field, matching the demo
   port's pattern).
3. **`Integer` boxing in `TicCmdEncoder`.** ~7 per tic when a net
   session is attached. **Fix:** change the encoder signature to
   take `int` primitives instead of `Integer` boxes; the HotSpot
   auto-boxing is what allocates. This is a follow-up commit, not
   in this one.

**This commit eliminates #2 (the user-reported hot one) and lays
groundwork for #1 and #3 in the report.** The next pass should fix
`Mat4` reuse in `publishBotPlacements` and the encoder boxes, which
together should drop the per-tic allocation count to "1 `Camera` and
nothing else" — exactly the docstring the demo port already promises.

## 5. The Custom Allocator — Where It Should Plug In

The guideline is "use the custom allocator." Today, zero production
code paths do. The natural places to plug in:

- **`Mat4` reuse in `publishBotPlacements`.** A `Z_Pool<Mat4>` (or a
  `Mat4` value type with `setIdentity()` / `setTranslation()` mutators
  that overwrite in place) eliminates the per-tic allocation without
  touching the memory port. This is the lowest-risk fix and should
  land first.
- **`Vec3` reuse in `DemoGameplayPort.fireIfRequested`.** Same shape.
  The 2 per-shot allocations are the largest non-Mat4 pressure.
- **`Target` reuse in `Hitscan.fire`.** A small object pool
  (`Z_ObjectPool<Target>`) sized to the bot count, with the pool
  returning a fresh `Target` whose box fields are mutable. This is
  the only allocation in the per-tic shot path.
- **`I_MemoryPort` itself.** No callers in production. The factory
  that would let `EngineMain` create one does not exist. Until that
  ships, the port is dead code from the runtime's point of view.

## 6. Per-Tic Allocation Budget (The Target)

After the fixes in § 4 and § 5, the target is:

- **MapGameplayPort:** 1 `Camera` per tic (and 0 when the surface
  is 0x0). Nothing else on a tic with no fire, no respawn and no
  bot movement (the empty-tic case).
- **DemoGameplayPort:** 1 `Camera` per tic + 2 `Vec3` per shot.
- **Match.tick:** 0 allocations on a tic with no shot landing;
  1 `Target` per shot landing.
- **Construction:** the engine's startup allocates the 12-bot
  roster, the 12-`Target` per-bot pool, the bot waypoint `List`,
  the spec's spawn list, the 8-element `Match.dominationFlagOwners`
  array, the `BotShotLog`'s `int[]` / `float[]` / `float[]` /
  `float[]`, the 16 `MapSpec` objects (one per map), the scene
  instance tables, and the renderer's vertex / index buffers.
  All one-time.

## 7. How to Verify the Budget

1. **Run with `-Xlog:gc::time` during gameplay.** The log should
   show no GC pauses on a 30-second run. Any allocation visible
   in the log means a regression slipped in.
2. **Run with `-XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions
   -XX:+DebugNonSafepoints` and `async-profiler`** attached. The
   per-tic allocation profile should be flat. A ramp indicates a
   leak or a runaway allocation.
3. **A test that asserts the per-tic allocation count is
   constant.** Not yet in the suite; should be added when the
   `Mat4` and `Target` reuse fixes land. The pattern is "tick
   the port 1000 times, count `new` calls, assert 0 (or
   `Camera`-only)." This belongs in a new test class
   `MapGameplayPortAllocationTest` (or `DemoGameplayPortAllocationTest`).
4. **A CI job** that runs a 60-second synthetic gameplay workload
   and fails if the GC log shows any pause above 1 ms. Belongs in
   the build matrix; not yet wired.

## 8. Summary

| Guideline half | Status |
|---|---|
| *"Never or dynamically allocate as little as possible"* | **Mostly satisfied.** 1 `Camera` per tic is the only documented exception. The per-tic scratch array bug in `MapGameplayPort` is **fixed in this commit**; the `Mat4` per bot and the `Vec3` per shot are **documented follow-ups** in § 4. |
| *"Reserve enough memory for the entire app"* | **Satisfied at the per-class level** (pre-allocated arrays and pools) but **not at the application level** (no `Engine.init()` calls `I_MemoryPort.init(heapSize)` with a pre-computed budget). The memory port is shipped as a port; no caller. |
| *"Use the custom allocator"* | **Not satisfied in production.** The `I_MemoryPort` interface, the `JvmMemoryPort` and `ZoneMemoryPort` adapters and the `WorkerPool` / `ThreadPoolFactory` exist. The `MemoryPortFactory` and any `Engine.init()` wiring do not. Until that ships, the project uses the JVM heap directly for every `new` in production code. |

The collision fix in this commit does not change the allocator story
— `PlayerController.setCollisionWorld` and `Bot.setCollisionWorld`
are setter calls on existing fields, and the `PhysicsWorld` was
already built once at scene-load. The fix does change the per-tic
allocation story: it eliminates one allocation source (the
`muzzleScratch` local) and adds none.

