# OpenFPS — Code Style Guide

> This document defines the coding conventions for all Java source in the OpenFPS engine.
> Compliance is enforced by Checkstyle. Rules here supersede Checkstyle defaults where noted.

---

## 1. File Structure

### 1.1 File Header

Every `.java` file must begin with this Javadoc comment (no blank line between `/*` and `*`):

```java
/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.<subsystem>;
```

A class in an `*.adapter.*` package must say so in its class Javadoc, and must
not import from core engine packages:

```java
/**
 * Null implementation of I_TimePort.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class NullTimePort implements I_TimePort
```

The one legitimate place core code touches an adapter is the composition root
(`EngineMain`), which by definition has to pick concrete implementations. No
other class in `core` may import from an `adapter` package.

### 1.2 Class and Interface Declarations

Braces on their own lines:

```java
// GOOD
public class MyClass
{
    // ...
}

// BAD
public class MyClass {
    // ...
}
```

### 1.3 Import Organization

Three blocks, separated by blank lines, sorted alphabetically within each block:

1. `java.*` — standard library
2. `javax.*` — extended standard library
3. `com.openfps.*` — engine packages

No wildcard imports except for `static` imports of constants.

---

## 2. Naming Conventions

### 2.1 Standard Java Conventions (enforced)

| Element | Convention | Example |
|---|---|---|
| Class / Interface | PascalCase | `ZoneAllocator`, `RendererPort` |
| Method | camelCase | `allocateBlock`, `submitFrame` |
| Field (instance) | camelCase | `currentTic`, `playerList` |
| Field (static final) | SCREAMING_SNAKE_CASE | `MAX_PLAYERS`, `ZONE_HEAP_SIZE` |
| Local variable | camelCase | `sectorIndex`, `deltaTime` |
| Parameter | camelCase | `wadFile`, `peerId` |
| Package | lowercase | `com.openfps.engine.hal` |
| Type parameter | single letter or PascalCase | `T`, `TEntity` |

### 2.2 Engine-Specific Naming Signals

| Pattern | Meaning |
|---|---|
| `I_*` | HAL port interface (e.g., `I_TimeSource`) |
| `D_*` | Core / game loop class prefix |
| `P_*` | Gameplay subsystem prefix |
| `R_*` | Render subsystem prefix |
| `S_*` | Audio subsystem prefix |
| `G_*` | Network subsystem prefix |
| `W_*` | Resource / WAD subsystem prefix |
| `Z_*` | Memory / zone allocator prefix |

These are class name prefixes only — not package names. Package names remain standard lowercase.

---

## 3. Immutability and Mutability

### 3.1 Prefer Immutability

Treat all fields as immutable by default. This mirrors Rust's `final` / `val` philosophy.

**Rules:**
- All instance fields must be `final`
- If a field genuinely needs mutation (e.g., particle positions), mark it explicitly with a comment: `// MUTABLE: updated every frame`
- Use `final` on every local variable unless you are reassigning it

```java
// GOOD
public void processTic(final int ticIndex)
{
    final PlayerState state = getPlayer(ticIndex);
    final int clampedHealth = Math.min(state.health(), MAX_HEALTH);
    render(clampedHealth);
}

// BAD
public void processTic(int ticIndex) {
    PlayerState state = getPlayer(ticIndex);
    state.health = 50; // mutation without marker
}
```

### 3.2 Mutable Collections

If a field holds a collection that is modified in-place, it must be a concrete mutable type (not an interface), and must have the `// MUTABLE` comment:

```java
/** List of connected peers. MUTABLE: modified on connect/disconnect. */
private final ArrayList<PeerConnection> peerList = new ArrayList<>();
```

---

## 4. Primitive Types

### 4.1 Primitive-First Policy

**Use primitive types everywhere.** Avoid boxing. This is critical for a game engine hot path.

```java
// GOOD
private final int playerHealth;
private final long tickStartNanos;

// BAD
private final Integer playerHealth;
private final Long tickStartNanos;
```

### 4.2 Primitive Arrays

For hot-path data (entity lists, tic buffers), use `int[]`, `float[]`, `long[]`. Do not use `List<Integer>`.

### 4.3 `@Var` / Mutable Local Variables (Project Valhalla Note)

When primitive mutability is needed locally, use a local variable without `final`. The convention:

```java
// MUTABLE local — used as a scratch counter in a loop
int totalDamage = 0;
for (final Entity e : entityList) {
    totalDamage += e.damage(); // read-only
}
```

The `final` keyword is **required on all parameters** and **strongly preferred on all other locals**. Only omit it when you are demonstrably reassigning.

---

## 5. Methods and Functions

### 5.1 Method Javadoc

Every non-private method must have a Javadoc comment **above the declaration**
— the standard Java position, which is what tooling, IDEs, and Checkstyle all
expect:

```java
/**
 * Submits a rendered frame to the display.
 * Blocks until vsync if enabled.
 *
 * @param frameNanos monotonic time in nanoseconds from engine start
 */
public void submitFrame(final long frameNanos)
{
    // implementation
}
```

`@param` / `@return` tags are encouraged wherever the name alone doesn't carry
the meaning (units, ranges, sentinel values, null behaviour). They are not
mechanically required — a one-line `/** Returns the frame rate in Hz. */` on an
accessor is complete documentation. What Checkstyle does enforce is that any tag
you *do* write matches the signature.

For private methods, a one-line `//` comment is acceptable if the method name is
self-explanatory.

### 5.2 Method Length

Target under 40 lines. If a method grows beyond 60 lines, extract logical blocks into private helpers.

### 5.3 Parameters

All parameters of a method **with a body** must be `final`:

```java
public void registerPeer(final int peerId, final InetAddress address)
```

Do **not** write `final` on parameters of an interface or abstract method
declaration. There is no body, so it has no meaning there, and Checkstyle's
`RedundantModifier` rejects it:

```java
// GOOD — interface declaration
void registerPeer(int peerId, InetAddress address);

// BAD — redundant, fails the build
void registerPeer(final int peerId, final InetAddress address);
```

### 5.4 Return Statements

Prefer early returns. Avoid flag variables when a direct return suffices.

### 5.5 No Ternary Operator

**The `?:` operator is banned.** Use `if`/`else`, an early return, or a
`switch`. Control flow should read as control flow, not as an expression
you have to unpack — especially inside a larger expression such as string
concatenation or an argument list, where the branch is easy to miss.

```java
// GOOD
public static int abs(final int value)
{
    if (value < 0)
    {
        return -value;
    }
    return value;
}

// GOOD — pull the branch out of the expression
int byteCount = 0;
if (payload != null)
{
    byteCount = payload.length;
}
return "NetworkPacketEvent{bytes=" + byteCount + "}";

// BAD
public static int abs(final int value)
{
    return value < 0 ? -value : value;
}

// BAD — branch buried inside a concatenation
return "NetworkPacketEvent{bytes=" + (payload == null ? 0 : payload.length) + "}";
```

Switch *expressions* (`case X -> value`) are not ternaries and are fine —
they are explicit, exhaustive, and checked by the compiler.

---

## 6. Lambdas and Functional Style

### 6.1 Simple Lambdas Are Fine

A single-operation, single-return lambda is acceptable:

```java
final Runnable onTick = () -> advanceTic();
executor.submit(onTick);
```

### 6.2 No Lambda Chains, No Nesting

**Never nest a lambda inside another lambda.** One level is the hard
limit — a lambda whose body contains another `->` is a review failure, no
exceptions. Nested closures make capture and control flow nearly
impossible to follow, and they are the single worst thing to land in a
stack trace from a worker thread.

Avoid chaining lambdas. If you need a lambda with more than one
expression, extract it to a named method.

```java
// GOOD
final Predicate<Entity> isAlive = Entity::isAlive;
stream.filter(isAlive).forEach(Entity::activate);

// BAD
stream.filter(e -> e.isAlive()).map(e -> { e.activate(); return e; }).collect(toList());
```

### 6.3 Method References

Prefer method references (`Entity::activate`) over verbose lambdas where the intent is clear.

---

## 7. Error Handling

### 7.1 No Checked Exceptions for Expected Conditions

Use `IllegalArgumentException` or `UnsupportedOperationException` for programmer errors. Use specific runtime exceptions for recoverable game logic errors.

### 7.2 No Swallowed Exceptions

Every `catch` block must either:
- Re-throw as a different exception with context
- Log the error
- Assert / fail the tic in a test

```java
// GOOD
} catch (final IOException e) {
    throw new ResourceException("Failed to load WAD lump: " + lumpName, e);
}

// BAD
} catch (final IOException e) {
    // do nothing
}
```

### 7.3 Assertions

Use `assert` for invariants that should never fail in production:

```java
assert ticIndex >= 0 : "ticIndex must be non-negative";
```

The `-ea` JVM flag is enabled in all test and dev runs.

---

## 8. Logging

- Use **SLF4J** for all logging (`org.slf4j:slf4j-api`, backed by Logback)
- Logger named per class: `private static final Logger LOG`
- Use `{}` placeholders — never string concatenation inside a log call
- Pass the exception as the LAST argument; it gets no `{}` placeholder
- Log levels: `DEBUG` (dev), `INFO` (milestones), `WARN` (degraded), `ERROR` (failure)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger LOG = LoggerFactory.getLogger(GameLoop.class);

LOG.info("GameLoop started: rate={} ({}ns/tic)", config.rate(), nanosPerTic);
LOG.error("Subsystem {} init() failed", id, e);   // exception last, no {} for it
```

**Banned**: `System.out` / `System.err`, `System.Logger`, `java.util.logging`,
and Log4j used directly. Everything goes through the SLF4J facade so the
backend stays swappable (Logback on desktop, an Android binding in Phase 3+).

---

## 9. Threading

- The game loop is the sole event producer and runs on its own dedicated
  thread (`openfps-gameloop`) — never on the worker pool, never on main
- **The main thread belongs to the platform**: it runs the window event pump
  (`I_WindowPort.pumpEvents()`), because GLFW requires window calls there.
  Headless, it simply joins the loop thread
- All event dispatch happens on the `WorkerPool` (N = logical cores / 2)
- Audio, network, and render each get their own thread when adapters are wired
- Cross-thread communication goes through the event bus, backed by a bounded
  `java.util.concurrent.LinkedBlockingQueue`. The bound IS the backpressure:
  `publish()` blocks when full rather than dropping events. (`LinkedBlockingQueue`
  over `ArrayBlockingQueue` because the capacity need not be pre-allocated.)
- Prefer `java.util.concurrent` primitives over `synchronized`: `AtomicLong` /
  `AtomicInteger` for counters, `volatile` for state flags, the blocking queue
  for handoff. A `synchronized` block requires a comment saying why an atomic
  won't do.

---

## 10. Testing Conventions

- Tests live in `src/test/java/com/openfps/engine/`
- Test class naming: `<ClassUnderTest>Test` (e.g., `MemoryPortTest`, `FrameRateTest`)
- Test method naming: `should<ExpectedBehavior>When<Condition>` (JUnit 5 style),
  or a plain descriptive name plus `@DisplayName`
- Group variants of one subject with `@Nested` (see `MemoryPortTest`)
- All tests must be deterministic — no unseeded random data
- Default to the null HAL adapter. The exception is a test that exists to cover
  a real adapter (`SqliteUserProfilePortTest`), which uses an in-memory SQLite
  database — never a file on disk, never a shared one.

---

## 11. Documentation to Code Map

The design notes for a subsystem live in **inline Javadoc on its port
interface** (see `I_MemoryPort`, `I_WadPort`) and in the package's
`README.md`. There is no separate `PORT.md` file — an earlier draft of this
guide referenced one; it never existed.

When you implement or change a module:

1. Update the subsystem's section in `PLAN.md` § 3 with implementation notes
2. Tick the matching `[x]` in the `PLAN.md` § 7 roadmap
3. Update the package `README.md` if the design changed
4. If you added a shared service or constant, add it to § 13 below

---

## 12. Tooling

| Tool | Version | Purpose |
|---|---|---|
| Java | 17 LTS | Source/target; runs on JVM 17+ |
| Gradle | 8.10 | Build (via wrapper) |
| Checkstyle | 10.18.0 | Style enforcement — wired to `build`, `maxWarnings = 0` |
| JUnit Jupiter | 5.11.4 | Testing |
| AssertJ | 3.26.3 | Test assertions |
| SLF4J | 2.0.16 | Logging facade |
| Logback | 1.5.12 | Logging backend |
| Xerial SQLite JDBC | 3.46.1.0 | User profile persistence (desktop) |
| LWJGL | 3.3.4 | Desktop graphics/audio/net (Phase 1.5, not yet a dependency) |

Checkstyle runs as part of `gradlew build` and fails the build on any
violation. Run it alone with `gradlew checkstyleMain`; the HTML report lands
in `build/reports/checkstyle/main.html`.

## Frame rate configuration

Frame rate is **not** a static constant. The engine supports three rates:
30, 60, 120 Hz, selected at startup via `--fps=N`. See
`core/FrameRate.java` and `core/GameConfig.java` for the math and
factory methods. The Constants class does **not** contain any rate
constants — that was removed in Phase 1.3.

---

## 13. Code Reuse — Use the Services We Already Have

**Rule: before writing new code, check whether the service or constant
already exists. If it does, USE IT. Do not reimplement.**

The engine has a small, fixed set of shared services and constants.
Every module uses them. New code that bypasses them is a bug.

### 13.1 The shared services (use these, don't reinvent)

| Service | Where it lives | When to use it |
|---|---|---|
| `I_MemoryPort` (port + factory + backends) | `com.openfps.engine.memory` | Every allocation. **Never** `new byte[]` outside a port implementation. |
| `I_EventBusPort` (port + factory) | `com.openfps.engine.core.eventbus` | All inter-subsystem / inter-component communication. |
| `WorkerPool` (via `I_ThreadPoolPort`) | `com.openfps.engine.core.pool` | All parallel work. Event dispatch drains the bus; `submitParallel(I_ParallelJob, jobCount)` is the index-based fan-out for data-parallel passes such as the renderer's tiles — the submitting thread participates, so it is safe to call from a worker and from `workerCount == 1`. **Never** `new Thread(...)` for engine work. |
| `I_SystemInfoPort` | `com.openfps.engine.hal.port` | Anything that depends on hardware — core count, memory, OS, JVM version. |
| `I_TimePort` | `com.openfps.engine.hal.port` | Anything that reads time. `nanos()`/`millis()` are monotonic (tic timing, durations); `epochMillis()` is wall clock (persisted timestamps). **Never** `System.nanoTime()` / `System.currentTimeMillis()` in engine code — and never store a monotonic reading as a date. |
| `I_InputPort`, `I_DatagramPort`, `I_FilePort` | `com.openfps.engine.hal.port` | HAL capabilities. |
| `I_UserProfilePort` | `com.openfps.engine.hal.port` | All user-profile reads/writes. The engine never touches SQLite or Room directly. Desktop impl is `SqliteUserProfilePort` (Xerial); Android impl (Phase 3+) will use Room. |
| `Subsystem` base class | `com.openfps.engine.core.subsystem` | Every new subsystem. **Don't** implement `Runnable` or your own state machine. |
| `SubsystemRegistry` | `com.openfps.engine.core.subsystem` | Registering / looking up / dispatching to subsystems. |
| `EventFactory` | `com.openfps.engine.core.event` | Building events with sequence numbers and timestamps. |
| `GameConfig` | `com.openfps.engine.core` | Holding rate + maxTics. **Don't** carry these as separate parameters. |
| `FrameRate` | `com.openfps.engine.core` | All frame-rate config. Closed enum — no other values. |
| `FixedMath` | `com.openfps.engine.common` | All 16.16 fixed-point arithmetic. |
| `NullAdapterFactory` | `com.openfps.engine.hal.adapter.nulladapter` | Default HAL for tests and headless. |
| `SqliteAdapterFactory` | `com.openfps.engine.hal.adapter.sqlite` | Desktop HAL — real on-disk profile persistence, null ports elsewhere. |
| `EngineMain.run(GameConfig, boolean, boolean)` | `com.openfps.engine.core` | Standard bootstrap (config, useSqlite, headless). |
| `MemoryPortFactory` | `com.openfps.engine.memory.factory` | Picking a memory backend. **Don't** instantiate `JvmMemoryPort` directly. |
| `EventBusFactory` | `com.openfps.engine.core.eventbus` | Picking an event bus. |
| `ThreadPoolFactory` | `com.openfps.engine.core.pool` | Picking a worker pool. |

### 13.2 The shared constants (use these, don't redeclare)

The `com.openfps.engine.common.Constants` class holds the engine-wide
static primitives. New code must read from it, not redeclare.

Most of these are **reserved for the phase that needs them** — only
`ZONE_HEAP_SIZE` and `ZONE_ALIGN` have callers today. That is deliberate: the
value is fixed here once so the phase that lands the feature reads it rather
than inventing a magic number.

| Constant | Value | Use it for |
|---|---|---|
| `Constants.MAX_PLAYERS` | 8 | Player slot bounds, net code, lobby. |
| `Constants.DEFAULT_NET_PORT` | 5021 | Default P2P port. |
| `Constants.TIC_BUFFER_SIZE` | 64 | Tic cmd ring buffer depth. |
| `Constants.MAX_LATENCY_TICS` | 5 | Latency tracking cap. |
| `Constants.ZONE_HEAP_SIZE` | 16 MB | Default zone heap (used by `MemoryPortFactory` calls). |
| `Constants.ZONE_ALIGN` | 8 | Per-allocation alignment. |
| `Constants.MAP_SCALE` | 65536 | 16.16 fixed-point scale for map coords. |
| `Constants.PLAYER_RADIUS` | 16 × MAP_SCALE | Collision radius. |
| `Constants.MAX_OPEN_HEIGHT` | 128 × MAP_SCALE | Sector ceiling cap. |
| `Constants.GRAVITY` | 8 × MAP_SCALE / 120² | Per-tic gravity. |
| `Constants.PLAYER_SPEED` | 256 × MAP_SCALE / 120 | Player movement speed (per tic, at 120 Hz). |
| `Constants.MAX_VELOCITY` | 50 × MAP_SCALE | Velocity clamp. |
| `Constants.MAX_ENTITIES` | 4096 | Per-map entity cap. |
| `Constants.NULL_ENTITY` | -1 | Sentinel for "no entity". |
| `Constants.ENTITY_EMPTY` | 0 | Sentinel for "empty slot". |

### 13.3 How to add a new constant or service

1. **Don't.** Search `Constants.java` and the existing ports first.
2. If you really need one, add it to `Constants` (for a primitive)
   or define a new port interface in the right package.
3. Update `STYLE.md` § 13 to include it in the table above.
4. Test the *behaviour* that depends on it, reading the constant rather than
   restating its literal value — so the test breaks if someone forks the value.
5. Update `PLAN.md` to mark it in the subsystem spec.

### 13.4 Anti-patterns (instant review failure)

- `new byte[size]` in engine code (use the memory port)
  - **Sanctioned exception — long-lived engine-owned primitive buffers.**
    Long-lived, engine-owned primitive buffers whose element type is **not**
    `byte`, allocated **once at initialisation and released at shutdown**, may
    be allocated directly. **Named sites only**, listed below. **A hot loop is
    NOT on its own a qualifying reason.**

    | Sanctioned site | Buffers | Allocated at |
    |---|---|---|
    | `render.adapter.Framebuffer` | `int[]` colour (RGBA8888), `float[]` depth (1/w) | `init(w, h)` and `resize(w, h)` only |

    Why the exception exists: `I_MemoryPort` hands out opaque `int` handles
    over a `byte[]` store and has no read or write operation, so per-pixel
    handle indirection is not a cost to measure — it is several times the cost
    of the pixel work itself. The port earns its keep tracking *many, small,
    short-lived* allocations (that is what tags and `freeByTag` are for); the
    framebuffer is two arrays allocated once and resized only when the window
    is. It gets none of the port's benefits and pays its whole cost. The full
    argument, including the typed-slab alternative that was rejected, is in
    `engine/src/main/java/com/openfps/engine/render/README.md` § 11(a).

    The wording admits `Framebuffer` and the audio mixing buffers, and excludes
    per-frame and per-entity allocation — which is what the rule actually
    exists to prevent. If a third and fourth candidate appear, the pattern is
    real and `I_MemoryPort` should grow a typed-slab capability instead;
    revisit § 11(a) at that point rather than lengthening the table.

- `System.nanoTime()` / `System.currentTimeMillis()` in engine code — use
  `I_TimePort`: `nanos()`/`millis()` for monotonic timing, `epochMillis()` for
  persisted timestamps. The only sanctioned direct callers are the time-port
  adapters themselves and shutdown-path timeouts that never feed simulation
  state (e.g. `WorkerPool.awaitTermination`). If a reading can influence
  lockstep, it must come from the port.
- `new Thread(...)` in engine code — event handling belongs on the `WorkerPool`.
  The producer side is the exception: the game loop cannot run on the pool it
  feeds (it would hold a consumer thread for the whole run and deadlock at
  `workerCount == 1`), so `EngineMain` runs it on the calling thread.
- `new HashMap<>()` / `new ArrayList<>()` in hot paths (primitive arrays or `I_MemoryPort` allocations)
- Magic numbers like `60`, `65536`, `4096` in engine code (use `Constants` or `FrameRate`)
- `new JvmMemoryPort(...)` in engine code (use `MemoryPortFactory`)
- `new SharedEventBus()` in engine code (use `EventBusFactory`)
- A subsystem class that `implements Runnable` (extend `Subsystem` instead)
- A new fixed-point math constant outside `FixedMath`
- A new frame rate value outside the `FrameRate` enum
- A new "memory pool" or "thread pool" class (extend what's there)
- A ternary `?:` anywhere (use `if`/`else` or `switch` — see § 5.5)
- A lambda nested inside another lambda (see § 6.2)

### 13.5 Why this rule exists

The engine is small enough that every service and constant is a
deliberate architectural choice. A second copy of a fixed-point
constant, a second time source, a second thread allocator — each
one is a divergence waiting to happen. The factory pattern (memory,
event bus, thread pool) exists precisely so the engine has exactly
one of each thing. Honor that.

---

*This style guide is a living document. Changes require a PR with rationale and Checkstyle config update.*
