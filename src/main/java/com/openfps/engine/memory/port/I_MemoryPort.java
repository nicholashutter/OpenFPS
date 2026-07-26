/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.port;

/**
 * Z_ Port interface — zone memory allocator.
 * Bypasses JVM GC for hot-path game object allocations.
 *
 * ====================================================================
 *  ZONE ALLOCATOR STRATEGY (Phase 2+ — references below)
 * ====================================================================
 *
 *  Full design in src/main/java/com/openfps/engine/memory/README.md.
 *  Summary:
 *
 *  1. BUMP POINTER (Phase 0 stub):
 *       offset = used
 *       used += aligned(size)
 *       return (long)offset
 *     O(1) allocate, no per-allocation metadata.
 *     No individual free — only full reset.
 *
 *  2. SLAB ALLOCATOR (Phase 2, for fixed-size objects):
 *       Pre-allocate N objects of size S at startup.
 *       free-list: each object has a header pointing to the next free one.
 *       alloc: pop from head, O(1)
 *       free: push to head, O(1)
 *     Used for entities, tic commands, sound channels.
 *     Source: Jeff Bonwick, "The Slab Allocator", USENIX 1994
 *     https://www.usenix.org/legacy/publications/library/proceedings/bos94/full_papers/bonwick.ps
 *
 *  3. TAG-BASED BULK FREE:
 *       Every allocation carries a tag (TAG_STATIC, TAG_GAME, etc.)
 *       On map change: freeByTag(TAG_GAME) frees all game-level data.
 *       Implemented either via:
 *         a) Per-tag free lists (slab per tag)
 *         b) Header-walk + skip pattern on the bump heap
 *     The DOOM approach is (a) — separate free lists per tag.
 *     Source: DOOM source z_zone.c
 *     https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/z_zone.c
 *
 *  4. ALIGNMENT:
 *       All allocations aligned to ZONE_ALIGN (= 8) bytes.
 *       Why 8? Matches long on 64-bit JVMs, so primitive array elements
 *       don't span cache lines unnecessarily.
 *
 *  5. WHY NOT JUST new?
 *       JVM allocation goes through the GC heap. The GC can stop the
 *       world at any moment, including mid-tic. For a 35 Hz game with
 *       a 28.5 ms budget, even a 1 ms GC pause is noticeable.
 *       Zone allocations are pointer bumps, not GC allocations, so
 *       they don't trigger GC. They live in a pre-reserved byte[].
 *
 *  WHEN TO USE WHAT:
 *    Zone:    map data, entities, decoded textures
 *    new:     short-lived temps, exception objects, lambdas
 *    Off-heap (Phase 3+):  large buffers, native interop
 *
 *  References:
 *    - Fabian Giesen, "The Ubiquitous Bump Allocator":
 *      https://fgiesen.wordpress.com/2012/04/03/the-ubiquitous-bump-allocator/
 *    - DOOM source z_zone.c (Z_Malloc, Z_Free, Z_FreeTags)
 *    - JEP 454 (Foreign Function & Memory API) for future off-heap work:
 *      https://openjdk.org/jeps/454
 */
public interface I_MemoryPort
{
    /** Allocation tag — static data, never freed. */
    int TAG_STATIC = 0;
    /** Allocation tag — game entities, freed on map change. */
    int TAG_GAME    = 1;
    /** Allocation tag — dynamically allocated, freed individually. */
    int TAG_DYNAMIC = 2;
    /** Allocation tag — lump cache, freed on flush. */
    int TAG_CACHE   = 3;

    /**
     * Allocates a block from the zone heap.
     *
     * @param sizeBytes minimum size in bytes (aligned to ZONE_ALIGN = 8)
     * @param tag allocation tag for bulk-free grouping
     * @return pointer-like long (raw address on native, offset in heap on JVM);
     *         returns 0L on allocation failure
     */
    long allocate(int sizeBytes, int tag);

    /**
     * Frees a previously allocated block.
     *
     * @param pointer the value returned by allocate()
     */
    void free(long pointer);

    /**
     * Frees all allocations with the given tag.
     * Used to clear level-specific data on map change.
     *
     * @param tag the tag to purge
     */
    void freeByTag(int tag);

    /**
     * Frees all allocations — full heap reset.
     */
    void reset();

    /**
     * Returns the number of bytes currently allocated.
     *
     * @return allocated bytes
     */
    int allocatedBytes();

    /**
     * Initializes the zone allocator with the given heap size.
     *
     * @param heapSizeBytes total heap size in bytes
     */
    void init(int heapSizeBytes);

    /**
     * Shuts down and releases the zone heap.
     */
    void shutdown();
}
