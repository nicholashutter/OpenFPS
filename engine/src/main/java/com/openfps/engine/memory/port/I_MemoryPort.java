/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.port;

/**
 * Z_ Port interface — unified memory allocation.
 *
 * The engine allocates memory through THIS interface exclusively. The
 * rest of the application does not know — and must not know — whether
 * the backing store is the JVM heap, a custom zone heap, or a slab
 * allocator. That choice lives behind a factory (see
 * {@code MemoryPortFactory}).
 *
 * ====================================================================
 *  STATE MACHINE
 * ====================================================================
 *
 *   UNINITIALIZED ──init()──► READY ──first allocate()──► ACTIVE
 *                                                  ▲
 *                                                  │ all operations
 *                                                  │
 *   ACTIVE ──shutdown()──► SHUTDOWN  (terminal)
 *   ACTIVE ──reset()─────► ACTIVE   (frees all, re-arms for new allocations)
 *   any   ──fatal error─► ERROR     (terminal, requires restart)
 *
 *  All transitions are explicit and validated. Invalid state requests
 *  throw {@link com.openfps.engine.memory.MemoryException} — never silent.
 *
 *  Why a state machine? The engine will eventually be event-loop driven.
 *  An allocator that's always callable is impossible to reason about
 *  during lifecycle events (engine start, map change, shutdown). The
 *  state machine makes the lifecycle explicit and testable.
 *
 * ====================================================================
 *  HANDLE SEMANTICS
 * ====================================================================
 *
 *  Allocations return int handles. Handles are NOT raw pointers; the
 *  client does not dereference them. The client passes the handle back
 *  to {@link #free(int)} or {@link #freeByTag(int)}. This abstraction
 *  lets the backend be either:
 *    - JVM heap (handle is a slot index into a tracking array)
 *    - Zone heap (handle is a heap offset)
 *    - Slab pool (handle is a slot index in a fixed-size array)
 *
 *  Handle values are backend-internal. The only operations defined on
 *  a handle are the ones on this interface.
 *
 *  The special handle {@link #NULL_HANDLE} is reserved for "no allocation"
 *  and is the return value of failed allocations.
 *
 * ====================================================================
 *  WHEN TO USE WHAT (Phase 2+ recommendations)
 * ====================================================================
 *
 *  Through this port (always):
 *    - Map data, entities, decoded textures
 *    - Hot-path pools
 *
 *  Through plain `new` (acceptable):
 *    - Engine boot (one-time)
 *    - Test code
 *    - Exception objects
 *    - Lambdas (always stack-allocated or JIT-scalarized)
 *
 *  The default factory in {@code MemoryPortFactory.createJvm()} is
 *  the right choice for most code. Switch to
 *  {@code MemoryPortFactory.createZone(...)} when you need:
 *    - Guaranteed bounded memory
 *    - Bulk-free on map change
 *    - Deterministic allocation timing for P2P
 *
 *  References:
 *    - DOOM source z_zone.c:  https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/z_zone.c
 *    - Bonwick, "The Slab Allocator" (USENIX 1994):
 *      https://www.usenix.org/legacy/publications/library/proceedings/bos94/full_papers/bonwick.ps
 *    - Fabian Giesen, "The Ubiquitous Bump Allocator":
 *      https://fgiesen.wordpress.com/2012/04/03/the-ubiquitous-bump-allocator/
 *    - OpenJDK ZGC:
 *      https://wiki.openjdk.org/display/zgc/Main
 */
public interface I_MemoryPort
{
    // ===============================================================
    //  Allocation tags — for bulk-free grouping
    // ===============================================================

    /** Static data, never freed (engine globals, ROM-style data). */
    int TAG_STATIC  = 0;
    /** Map-level data, freed on map change (entities, level geometry). */
    int TAG_GAME    = 1;
    /** Dynamic per-tic data, freed individually. */
    int TAG_DYNAMIC = 2;
    /** Lump cache, freed on flush (decoded textures, sounds). */
    int TAG_CACHE   = 3;

    /** Sentinel handle meaning "no allocation". */
    int NULL_HANDLE = -1;

    // ===============================================================
    //  State machine
    // ===============================================================

    /**
     * Allocator lifecycle states. Transitions are validated — see the
     * state diagram in the class Javadoc.
     */
    enum State
    {
        /** Default state at construction. Must call init() to advance. */
        UNINITIALIZED,
        /** Heap allocated, ready for the first allocation. */
        READY,
        /** At least one allocation has been made; the allocator is in use. */
        ACTIVE,
        /** Terminal state. All operations throw. */
        SHUTDOWN,
        /** Terminal error state. The engine must be restarted. */
        ERROR
    }

    // ===============================================================
    //  Lifecycle (state machine transitions)
    // ===============================================================

    /**
     * UNINITIALIZED → READY. Allocates the backing heap.
     * Must be called exactly once before any other operation.
     *
     * @param heapSizeBytes total heap size in bytes
     * @throws com.openfps.engine.memory.MemoryException if state is not UNINITIALIZED
     */
    void init(int heapSizeBytes);

    /**
     * ACTIVE → SHUTDOWN. Releases the backing heap. After this call,
     * every other method on this port throws.
     */
    void shutdown();

    /**
     * ACTIVE → ACTIVE. Frees all live allocations. The allocator is
     * rearmed for new allocations. Tags and state are preserved.
     */
    void reset();

    /**
     * Returns the current state.
     */
    State state();

    // ===============================================================
    //  Allocation
    // ===============================================================

    /**
     * Allocates a block of memory.
     *
     * @param sizeBytes minimum size in bytes (will be aligned)
     * @param tag allocation tag for bulk-free grouping
     * @return a handle to the allocation, or {@link #NULL_HANDLE} if the
     *         allocator cannot satisfy the request
     * @throws com.openfps.engine.memory.MemoryException if state is not ACTIVE
     */
    int allocate(int sizeBytes, int tag);

    /**
     * Frees a previously allocated block by handle.
     *
     * @param handle the value returned by allocate()
     * @throws com.openfps.engine.memory.MemoryException if handle is invalid
     *         or state is not ACTIVE
     */
    void free(int handle);

    /**
     * Frees all allocations with the given tag.
     *
     * @param tag the tag to purge
     * @return number of allocations freed
     * @throws com.openfps.engine.memory.MemoryException if state is not ACTIVE
     */
    int freeByTag(int tag);

    // ===============================================================
    //  Introspection (for tests and diagnostics)
    // ===============================================================

    /** Total backing-store size in bytes. */
    int totalBytes();

    /** Number of bytes currently allocated. */
    int allocatedBytes();

    /** Number of bytes currently free in the backing store. */
    int freeBytes();

    /**
     * Returns the largest payload size (in bytes) that could be successfully
     * allocated right now. Accounts for backend-specific overhead (e.g.
     * zone port's per-allocation header). Use this for capacity checks
     * before calling {@link #allocate(int, int)}.
     */
    int maxAllocatable();

    /** Number of live allocations. */
    int handleCount();

    /** Returns the size in bytes of the allocation behind a handle, or 0 if invalid. */
    int sizeOf(int handle);
}
