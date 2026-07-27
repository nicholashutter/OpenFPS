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

Files in `*.adapter.*` packages additionally include:

```java
/**
 * Platform adapter — do not import from core engine packages.
 */
```

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
| Field (static final) | SCREAMING_SNAKE_CASE | `TIC_RATE`, `MAX_PLAYERS` |
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

Every non-private method must have a Javadoc comment at the **beginning** of the method body (not above the declaration):

```java
public void submitFrame(final long frameNanos)
{
    /**
     * Submits a rendered frame to the display.
     * Blocks until vsync if enabled.
     *
     * @param frameNanos monotonic time in nanoseconds from engine start
     */
    // implementation
}
```

For private methods, a one-line `//` comment is acceptable if the method name is self-explanatory.

### 5.2 Method Length

Target under 40 lines. If a method grows beyond 60 lines, extract logical blocks into private helpers.

### 5.3 Parameters

All parameters must be `final`:

```java
public void registerPeer(final int peerId, final InetAddress address)
```

### 5.4 Return Statements

Prefer early returns. Avoid flag variables when a direct return suffices.

---

## 6. Lambdas and Functional Style

### 6.1 Simple Lambdas Are Fine

A single-operation, single-return lambda is acceptable:

```java
final Runnable onTick = () -> advanceTic();
executor.submit(onTick);
```

### 6.2 No Lambda Chains

Avoid chaining lambdas. Do not nest lambdas. If you need a lambda with more than one expression, extract it to a named method.

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

- Use `System.Logger` (JUL-backed, no external dependency) for all logging
- Logger named per class: `private static final System.Logger LOG`
- Log levels: `DEBUG` (dev), `INFO` (milestones), `WARN` (degraded), `ERROR` (failure)

```java
private static final System.Logger LOG = System.getLogger(PlayerState.class.getName());

LOG.log(Level.INFO, "Engine initialized, tic rate: {0} Hz", TIC_RATE);
```

---

## 9. Threading

- The game loop runs on a single dedicated thread by default
- Audio, network, and render each get their own thread when adapters are wired
- All cross-thread communication goes through thread-safe queues (`java.util.concurrent.ArrayBlockingQueue`)
- No `synchronized` blocks; prefer `java.util.concurrent` primitives

---

## 10. Testing Conventions

- Tests live in `src/test/java/com/openfps/engine/`
- Test class naming: `<Subsystem>Test` (e.g., `ZoneAllocatorTest`)
- Test method naming: `should<ExpectedBehavior>When<Condition>` (JUnit 5 style)
- All tests must be deterministic — no `Thread.sleep`, no random data without seed
- Null HAL adapter is used for all tests; no platform-specific code in tests

---

## 11. Documentation to Code Map

Every module's implementation in `PLAN.md` section 3 has a corresponding `PORT.md`
informal note in its `port/` package (optional, can be inline Javadoc). When a module
is implemented, the implementer updates `PLAN.md` section 7 roadmap.

---

## 12. Tooling

| Tool | Version | Purpose |
|---|---|---|
| Gradle | 8.x | Build |
| Checkstyle | 10.18.0 | Style enforcement |
| JUnit 5 | 5.11.x | Testing |
| LWJGL | 3.3.4 | Desktop graphics/audio/net (future) |

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
| `WorkerPool` (via `I_ThreadPoolPort`) | `com.openfps.engine.core.pool` | All parallel work. **Never** `new Thread(...)` for engine work. |
| `I_SystemInfoPort` | `com.openfps.engine.hal.port` | Anything that depends on hardware — core count, memory, OS, JVM version. |
| `I_TimePort` | `com.openfps.engine.hal.port` | Anything that reads time. **Never** `System.nanoTime()` / `System.currentTimeMillis()` in engine code. |
| `I_InputPort`, `I_NetworkPort`, `I_FilePort` | `com.openfps.engine.hal.port` | HAL capabilities. |
| `Subsystem` base class | `com.openfps.engine.core.subsystem` | Every new subsystem. **Don't** implement `Runnable` or your own state machine. |
| `SubsystemRegistry` | `com.openfps.engine.core.subsystem` | Registering / looking up / dispatching to subsystems. |
| `EventFactory` | `com.openfps.engine.core.event` | Building events with sequence numbers and timestamps. |
| `GameConfig` | `com.openfps.engine.core` | Holding rate + maxTics. **Don't** carry these as separate parameters. |
| `FrameRate` | `com.openfps.engine.core` | All frame-rate config. Closed enum — no other values. |
| `FixedMath` | `com.openfps.engine.common` | All 16.16 fixed-point arithmetic. |
| `NullAdapterFactory` | `com.openfps.engine.hal.adapter.nulladapter` | Default HAL for tests and headless. |
| `EngineMain.runHeadless(GameConfig)` | `com.openfps.engine.core` | Standard bootstrap. |
| `MemoryPortFactory` | `com.openfps.engine.memory.factory` | Picking a memory backend. **Don't** instantiate `JvmMemoryPort` directly. |
| `EventBusFactory` | `com.openfps.engine.core.eventbus` | Picking an event bus. |
| `ThreadPoolFactory` | `com.openfps.engine.core.pool` | Picking a worker pool. |

### 13.2 The shared constants (use these, don't redeclare)

The `com.openfps.engine.common.Constants` class holds the engine-wide
static primitives. New code must read from it, not redeclare.

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
4. Add a test that verifies the constant is used (not a magic number).
5. Update `PLAN.md` to mark it in the subsystem spec.

### 13.4 Anti-patterns (instant review failure)

- `new byte[size]` in engine code (use the memory port)
- `System.nanoTime()` / `System.currentTimeMillis()` in engine code (use `I_TimePort`)
- `new Thread(...)` in engine code (use `WorkerPool`)
- `new HashMap<>()` / `new ArrayList<>()` in hot paths (primitive arrays or `I_MemoryPort` allocations)
- Magic numbers like `60`, `65536`, `4096` in engine code (use `Constants` or `FrameRate`)
- `new JvmMemoryPort(...)` in engine code (use `MemoryPortFactory`)
- `new SharedEventBus()` in engine code (use `EventBusFactory`)
- A subsystem class that `implements Runnable` (extend `Subsystem` instead)
- A new fixed-point math constant outside `FixedMath`
- A new frame rate value outside the `FrameRate` enum
- A new "memory pool" or "thread pool" class (extend what's there)

### 13.5 Why this rule exists

The engine is small enough that every service and constant is a
deliberate architectural choice. A second copy of a fixed-point
constant, a second time source, a second thread allocator — each
one is a divergence waiting to happen. The factory pattern (memory,
event bus, thread pool) exists precisely so the engine has exactly
one of each thing. Honor that.

---

*This style guide is a living document. Changes require a PR with rationale and Checkstyle config update.*
