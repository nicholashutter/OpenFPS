# Memory (Z_) — Zone Allocator

> Z_ is the engine's custom memory allocator. It bypasses the JVM garbage
> collector for game objects and provides tag-based bulk free on map change.

## What lives here (planned)

- `ZoneHeap` — pre-allocated byte array, served by a free-list or bump pointer
- `ZoneTag` — enumeration of allocation categories
- `ZoneStats` — debug-mode stats (peak usage, per-tag breakdown)
- `I_MemoryPort` — port interface (in `port/`)

## Subsystem layout

```
memory/
├── port/
│   └── I_MemoryPort.java   interface — allocate / free / freeByTag
└── adapter/
    └── NullMemoryPort.java bump-pointer over a byte[]
```

## Why custom allocation?

JVM garbage collection is great for general applications but bad for games:
- **Stop-the-world pauses** cause tic skips, even with ZGC.
- **Allocation pressure** keeps GC busy and burns CPU.
- **Unpredictable** — when does the next GC happen?

Game objects (entities, map lumps, level data) have **predictable lifetimes**.
The original DOOM solved this with the `Z_Zone` system:

1. Pre-allocate one large heap at startup (e.g. 16 MB).
2. Allocate by bumping a pointer — no per-allocation header.
3. Free in bulk by **tag** (e.g. `TAG_GAME`) when a map ends.
4. Individual free() is a no-op; you just reset on the next level.

**Source — DOOM source `z_zone.c`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/z_zone.c

**Source — "Memory Management for Game Programmers" — Fabian Giesen**:
https://fgiesen.wordpress.com/2012/04/03/the-ubiquitous-bump-allocator/

## Bump-pointer allocator

Simplest possible allocator:

```java
class BumpHeap {
    byte[] heap;
    int    used;
    int    size;
    
    long allocate(int n) {
        n = align(n);
        if (used + n > size) return 0;  // OOM
        int offset = used;
        used += n;
        return offset;
    }
    
    void reset() {
        used = 0;  // frees everything
    }
}
```

**Pros:** O(1) allocate, no fragmentation, perfect cache locality.
**Cons:** No individual free, can't grow, OOM-on-overflow kills the engine.

## Tag-based bulk free

Allocation tags (from `I_MemoryPort`):

| Tag | Used for | Freed when |
|---|---|---|
| `TAG_STATIC` | Engine globals, never freed | Never |
| `TAG_GAME` | Map data, entities, monsters | Map change |
| `TAG_DYNAMIC` | One-shot temp data | Per-tic or per-event |
| `TAG_CACHE` | Decoded textures, sounds | Lump flush |

The engine calls `freeByTag(TAG_GAME)` on map change. Individual `free()`
calls inside a level are **no-ops** — the bump pointer doesn't track per-allocation
metadata.

For real per-allocation tracking we'd use a **slab allocator** or
**free-list** with headers. That's a Phase 2+ optimization.

**Source — "Slab Allocator" — Jeff Bonwick, USENIX 1994**:
https://www.usenix.org/legacy/publications/library/proceedings/bos94/full_papers/bonwick.ps

## Debug-mode overhead

In `-ea` (assertion-enabled) mode, we can:
- Track per-allocation size in a side `int[]` for OOM debugging
- Verify the tag of every free matches the allocator's expectations
- Print per-tag usage stats every 100 tics

In production mode, all of this is `#ifdef`-stripped.

## Native vs. JVM heap

We keep the zone heap on the **JVM heap** (a `byte[]`) for simplicity. A future
optimization is to use `sun.misc.Unsafe` or `java.lang.foreign.MemorySegment`
to allocate a real off-heap region. This is what LWJGL3 does for native buffers.

**Source — "Off-heap memory in Java" — Aleksey Shipilëv, 2013**:
https://shipilev.net/jvm/anatomy-quarks/4-heap-vs-native/

**Source — Project Panama (Foreign Function & Memory API) — JEP 454**:
https://openjdk.org/jeps/454

## When to use the zone allocator

- ✅ Map data (LINEDEFS, SECTORS, THINGS)
- ✅ Entities
- ✅ Decoded textures
- ✅ Sound buffers
- ❌ Short-lived temporaries (use stack / `new`)
- ❌ Anything that must outlive a level

## Performance constraints

- **One allocation per tic, max**, for predictable GC behavior.
- **Heap size = 16 MB default**, configurable via `Constants.ZONE_HEAP_SIZE`.
- **Alignment = 8 bytes** (matches `long` on 64-bit JVMs).
- **No thread-local heaps yet** — single-threaded access only.

## Files

- `port/I_MemoryPort.java`
- `adapter/NullMemoryPort.java` (current bump-pointer impl)

## TODO (Phase 2)

- `ZoneHeap` — replace the stub with a real free-list + tag table
- `ZoneStats` — per-tag counters (debug builds only)
- Slab allocator for fixed-size objects (entities, tic commands)
