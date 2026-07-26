/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.port;

/**
 * Z_ Port interface — zone memory allocator.
 * Bypasses JVM GC for hot-path game object allocations.
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
