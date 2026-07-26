# AGENTS.md — OpenFPS Engine Agent Instructions

This file provides project-specific context and conventions for AI coding agents
working in this repository. It supplements, not replaces, the agent's built-in
persona (Mavis) and the global system prompt.

---

## Project Summary

**Name**: OpenFPS
**Type**: Game engine (FPS, peer-to-peer networking, JVM)
**Language**: Java 17 (source/target), running on JVM 17+
**Build**: Gradle 8.x (Kotlin DSL)
**License**: MIT

---

## Critical Conventions

### Immutability-First
- All instance fields MUST be `final`
- All method parameters MUST be `final`
- Local variables SHOULD be `final` unless reassigned
- No chaining of lambdas; single-operation lambdas OK
- Prefer primitives over boxed types everywhere

### Brace Style
- K&R variant: **braces on their own lines**
  ```java
  public void foo()
  {
      if (condition)
      {
          doThing();
      }
  }
  ```

### Adapter/Port Architecture
- `port/` packages contain interfaces only
- `adapter/` packages contain implementations
- Core engine NEVER imports from an `adapter/` package
- Platform-specific code lives only in `hal.adapter.*`

### Naming
- Follow standard Java conventions (PascalCase classes, camelCase methods/fields)
- Engine prefix signals: `D_*` core, `P_*` gameplay, `R_*` render, `S_*` audio, `G_*` net, `W_*` resource, `Z_*` memory, `I_*` HAL port
- Static final constants: `SCREAMING_SNAKE_CASE`

### Documentation
- Every non-private method: Javadoc at method beginning
- Complex logic blocks: inline `//` comments
- `PLAN.md` section 7 is the living roadmap; update it when implementing modules
- `STYLE.md` is the authoritative style guide

---

## File Locations

| What | Where |
|---|---|
| Project plan | `PLAN.md` |
| Style guide | `STYLE.md` |
| Build instructions | `BUILD.md` |
| Checkstyle config | `config/checkstyle/checkstyle.xml` |
| Source | `src/main/java/com/openfps/engine/` |
| Tests | `src/test/java/com/openfps/engine/` |

---

## Build Commands

```powershell
# Build (desktop)
.\gradlew build

# Run tests only
.\gradlew test

# Checkstyle only
.\gradlew checkstyleMain

# Run the engine (when main exists)
.\gradlew run

# Android profile (requires Android SDK)
.\gradlew build -Pandroid
```

---

## Common Patterns

### Port Interface Pattern
```java
package com.openfps.engine.<subsystem>.port;

/**
 * Port interface for <subsystem>.
 * Implemented by platform adapters; called by core engine.
 */
public interface I_<Capability>Port
{
    void init();
    void shutdown();
    Result execute(final Input in);
}
```

### Immutable Data Record
```java
public final class PlayerState
{
    private final int health;
    private final int armor;
    private final long x;  // fixed-point

    public PlayerState(final int health, final int armor, final long x)
    {
        this.health = health;
        this.armor = armor;
        this.x = x;
    }

    public int health()   { return health; }
    public int armor()    { return armor; }
    public long x()       { return x; }
}
```

### Zone Allocator Usage
```java
private final I_MemoryPort memory = MemoryPortFactory.get();

public void spawnEntity(final int entityId)
{
    final long ptr = memory.allocate(ZoneTag.GAME_ENTITY);
    // ...
}
```

---

## What Not To Do

- Do NOT add external libraries without discussion
- Do NOT create `public static void main()` in non-core packages
- Do NOT write to `System.out` / `System.err` in production code — use `System.Logger`
- Do NOT import `java.util.List<Integer>` or any boxed collection in hot paths
- Do NOT add Android-specific code outside `hal.adapter.mobile`
- Do NOT skip updating `PLAN.md` section 7 when completing a roadmap item

---

## Subsystem Owners (Living)

| Subsystem | Package | Status |
|---|---|---|
| Core Loop | `core` | Pre-alpha |
| Gameplay | `gameplay` | Stub |
| Render | `render` | Stub |
| Audio | `audio` | Stub |
| Network | `net` | Stub |
| Resource | `resource` | Stub |
| Memory | `memory` | Stub |
| HAL | `hal` | Ports defined, adapters stubbed |

---

*Update this file when the project structure or conventions change.*
