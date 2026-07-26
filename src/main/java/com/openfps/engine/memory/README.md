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
   goes through `port.allocate(size, tag)`.

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

43 tests cover (both backends, parameterized):

- **State machine** — every transition, every invalid transition
- **Positive** — happy-path allocate/free/freeByTag/reset
- **Negative** — invalid args, bad handles, double-free, out-of-bounds
- **Overflow** — single oversized alloc, cumulative overflow, OOM recovery
- **Underflow** — free of unknown handle, freeByTag with no matches
- **Random** — 1000-iter stress with random alloc/free/freeByTag
- **Tags** — isolation between tags, all four tags coexisting
- **Max allocatable** — accounts for backend overhead

Run with: `.\gradlew.bat test`
