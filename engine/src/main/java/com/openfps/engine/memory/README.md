# Memory (Z_) — Zone / Heap Allocator

> Z_ is the engine's **unified memory port**. The entire engine allocates
> memory through `I_MemoryPort` — the rest of the code does not know
> whether the backing store is the JVM heap, a custom zone, or a slab.
> A factory at engine boot decides the backend.

## What lives here

```
memory/
├── port/
│   └── I_MemoryPort.java     the only memory API the engine uses
├── adapter/
│   ├── JvmMemoryPort.java    default — new byte[] + tracking
│   └── ZoneMemoryPort.java   bulk-free — bump pointer on a single byte[]
├── factory/
│   └── MemoryPortFactory.java  system-level backend selector
├── MemoryException.java        thrown for any invalid operation
└── README.md                   (this file)
```

## Architecture — system-level abstraction

The user said: *"I don't want there to be multiple approaches to memory
in this application."* This is enforced by:

1. **One interface** — `I_MemoryPort` is the only memory API the engine calls.
2. **One factory** — `MemoryPortFactory` is the only thing that picks a backend.
3. **No `new byte[]` outside the port** — every allocation in engine code
   goes through `port.allocate(size, tag)`, with exactly one written-down
   exception (below).

The factory hides the choice. Production launch can swap the JVM backend
for the zone backend with a single line change at boot:

```java
// Engine boot
I_MemoryPort memory = MemoryPortFactory.createJvm(16 * 1024 * 1024);
// or
I_MemoryPort memory = MemoryPortFactory.createZone(16 * 1024 * 1024);
// or (Phase 2+)
I_MemoryPort memory = MemoryPortFactory.createSlab(16 * 1024 * 1024, 128);

memory.init(16 * 1024 * 1024);  // UNINITIALIZED → READY
int handle = memory.allocate(1024, I_MemoryPort.TAG_GAME);
memory.free(handle);
memory.shutdown();
```

## The one sanctioned bypass — `render.adapter.Framebuffer`

Rule 3 has exactly one exception, and it is recorded here rather than only in
the renderer's docs because a reader who reads this file alone would otherwise
finish it believing there are none. `STYLE.md` § 13.4 names the site:
`render.adapter.Framebuffer` takes its `int[]` colour buffer and `float[]` depth
buffer straight from the JVM, at `init(w, h)` and `resize(w, h)` and nowhere
else.

**The reason is not that the rasterizer is hot.** A hot loop is explicitly *not*
a qualifying reason — if it were, the rule would have no force left. The reason
is that `I_MemoryPort` hands out opaque `int` handles over a `byte[]` store and
has **no read or write operation at all**. There is no way to touch a pixel
through this API; the buffers are `int[]` and `float[]` rather than `byte[]`;
and per-pixel handle indirection would cost several times the pixel work it
wraps. Meanwhile everything this port is *for* — tags, `freeByTag`, bulk release
of many small short-lived allocations — applies to nothing the framebuffer does.
It is two arrays that live from init to shutdown. It gets none of the port's
benefit and pays its whole cost.

So the exception is scoped to that *shape*, not to that class:

> Long-lived, engine-owned primitive buffers whose element type is not `byte`,
> allocated once at initialisation and released at shutdown, may be allocated
> directly. Named sites only, listed in `STYLE.md` § 13.4. A hot loop is **not**
> on its own a qualifying reason.

That wording admits `Framebuffer` and the future audio mixing buffers, and
excludes per-frame and per-entity allocation — which is what rule 3 actually
exists to prevent.

**The alternative that was rejected is the one this package would have grown.**
`I_MemoryPort` could gain a typed-slab operation returning the array itself,
with the port keeping the budget and lifecycle; `MemoryPortFactory.createSlab`
already exists as a Phase 2+ placeholder, so the concept is anticipated. It was
rejected on cost and timing: it weakens the port's central invariant — that the
engine never dereferences memory it did not get a handle for — and both backends
plus their 35 tests would have to absorb it, before a single pixel had been
drawn. If a third and fourth candidate ever appear, the pattern is real and the
typed slab becomes the better shape.

The full argument, including the option that lost, is at
`engine/src/main/java/com/openfps/engine/render/README.md` § 11(a). The
normative list of sanctioned sites is `STYLE.md` § 13.4 — an undocumented
exception is indistinguishable from a violation, so nothing may bypass this port
without appearing in that table.

## State machine

The port is a strict state machine. Every operation validates current state
and throws `MemoryException` for any invalid transition:

```
  +---------------+
  | UNINITIALIZED |  --init(heapSize)--------> READY
  +---------------+                            |
                                              | first allocate() --> ACTIVE
                                              |   |
                                              v   v
                                          +--------+
                                          | ACTIVE | --shutdown()-----> SHUTDOWN (terminal)
                                          +--------+ --reset()---------> READY
                                              |
                                              v
                                          +-------+
                                          | ERROR |  (terminal — restart required)
                                          +-------+
```

States: `UNINITIALIZED`, `READY`, `ACTIVE`, `SHUTDOWN`, `ERROR`.

**No silent failures.** Every invalid state request throws:

| State | `init` | `allocate` | `free` | `freeByTag` | `reset` | `shutdown` |
|---|---|---|---|---|---|---|
| `UNINITIALIZED` | ✓ | ✗ throw | ✗ throw | ✗ throw | ✗ throw | ✗ throw |
| `READY` | ✗ throw | ✓ | ✗ throw | ✓ | ✓ | ✓ |
| `ACTIVE` | ✗ throw | ✓ | ✓ | ✓ | ✓ | ✓ |
| `SHUTDOWN` | ✗ throw | ✗ throw | ✗ throw | ✗ throw | ✗ throw | ✗ throw |
| `ERROR` | ✗ throw | ✗ throw | ✗ throw | ✗ throw | ✗ throw | ✗ throw |

## Backends

### `JvmMemoryPort` (default)

- Each `allocate` creates a `new byte[size]`
- Parallel `byte[][] slots` tracks live references
- `free` drops the reference (GC reclaims later)
- `reset` clears all references
- OOM detection: `currentAllocated + size > totalBytes`

**Best for:** general-purpose development, most production code.

### `ZoneMemoryPort` (bulk-free)

- Single pre-allocated `byte[]` of fixed size
- Each allocation has an 8-byte header: `[size:4][tag:2][pad:2]`
- Bump pointer for `allocate` (O(1))
- `free` marks the slot as freed in a parallel `boolean[] live` bitmap;
  **bytes are not reclaimed until `freeByTag` or `reset`**
- `freeByTag` walks the heap, marks matching slots as freed
- OOM detection: `used + header + payload > heapSize`

**Best for:** entity pools, map data with bulk-free on map change, P2P
lockstep determinism.

### `ZoneSlabPort` (Phase 2+)

Planned: a zone port pre-partitioned into equal-sized blocks, giving
O(1) alloc and O(1) free with zero fragmentation. Best for fixed-size
hot-path objects (entities, tic commands).

## API quick reference

```java
public interface I_MemoryPort
{
    // Tags
    int TAG_STATIC  = 0;
    int TAG_GAME    = 1;
    int TAG_DYNAMIC = 2;
    int TAG_CACHE   = 3;
    int NULL_HANDLE = -1;

    enum State { UNINITIALIZED, READY, ACTIVE, SHUTDOWN, ERROR }

    // Lifecycle
    void init(int heapSizeBytes);
    void shutdown();
    void reset();
    State state();

    // Allocation
    int allocate(int sizeBytes, int tag);
    void free(int handle);
    int freeByTag(int tag);

    // Introspection
    int totalBytes();
    int allocatedBytes();
    int freeBytes();
    int maxAllocatable();    // largest payload that fits right now
    int handleCount();
    int sizeOf(int handle);
}
```

## Why a custom allocator? (Honest take)

**The DOOM-style zone allocator in modern Java is mostly cargo culting.**
The original motivations (no FPU, no GC, bare-metal) don't apply to
JVM 17+ with ZGC. Today, `new byte[size]` on a 64-bit JVM is a TLAB
bump-pointer — extremely fast — and ZGC pauses are sub-millisecond.

**BUT** the zone pattern still has real value for *specific* cases:

1. **Bulk free on map change** — much faster than waiting for GC
2. **Entity pools** — fixed-size, reused thousands of times/sec
3. **Bounded memory** — engine can't exceed a hard cap
4. **P2P determinism** — predictable allocation timing

**Right architecture (which is what we have):**

- `I_MemoryPort` = the **only** memory API the engine uses
- Multiple backends: `JvmMemoryPort` (default, `new`/GC) and `ZoneMemoryPort` (bulk-free)
- Factory picks the backend at startup
- Engine code is oblivious to which backend is in use

This satisfies "no multiple approaches" (one port, one API) AND
"system-level abstraction" (factory hides the choice).

References:
- DOOM source `z_zone.c`: https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/z_zone.c
- Bonwick, "The Slab Allocator" (USENIX 1994): https://www.usenix.org/legacy/publications/library/proceedings/bos94/full_papers/bonwick.ps
- Fabian Giesen, "The Ubiquitous Bump Allocator": https://fgiesen.wordpress.com/2012/04/03/the-ubiquitous-bump-allocator/
- OpenJDK ZGC: https://wiki.openjdk.org/display/zgc/Main

## Tests

35 tests cover (both backends, parameterized):

- **State machine** — every transition, every invalid transition
- **Positive** — happy-path allocate/free/freeByTag/reset
- **Negative** — invalid args, bad handles, double-free, out-of-bounds
- **Overflow** — single oversized alloc, cumulative overflow, OOM recovery
- **Underflow** — free of unknown handle, freeByTag with no matches
- **Random** — 1000-iter stress with random alloc/free/freeByTag
- **Tags** — isolation between tags, all four tags coexisting
- **Max allocatable** — accounts for backend overhead

Run with: `.\gradlew.bat test`
