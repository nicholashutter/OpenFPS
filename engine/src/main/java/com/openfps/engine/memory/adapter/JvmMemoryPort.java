/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.adapter;

import com.openfps.engine.memory.MemoryException;
import com.openfps.engine.memory.port.I_MemoryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM-heap memory port — default backend.
 *
 * Each {@link #allocate(int, int)} call creates a fresh {@code byte[]}
 * on the JVM heap. A parallel tracking array records the size and tag
 * so {@link #free(int)} and {@link #freeByTag(int)} can clean up.
 *
 * Performance characteristics:
 *   - {@code allocate}: TLAB bump-pointer fast path in HotSpot, sub-nanosecond
 *   - {@code free}: zero-cost (just drops the reference; GC reclaims later)
 *   - {@code reset}: O(n) walk over live handles, sets references to null
 *   - {@code freeByTag}: O(n) walk, sets references to null for matching tag
 *
 * What this port gives us:
 *   - Bounded heap (cannot exceed the configured size)
 *   - Handle-based access (engine never sees a raw reference)
 *   - Bulk-free by tag
 *   - State machine guarantees
 *
 * What this port does NOT give us (use ZoneMemoryPort for these):
 *   - Deterministic allocation timing
 *   - Truly bounded memory (GC heap may still grow for other reasons)
 *   - Zero-pause bulk free (we wait for GC eventually)
 *
 * The JVM GC will still reclaim the byte[] once no strong reference remains.
 * Our tracking array drops the reference on free; the array becomes eligible
 * for the next GC cycle.
 */
public final class JvmMemoryPort implements I_MemoryPort
{
    private static final Logger LOG = LoggerFactory.getLogger(JvmMemoryPort.class);

    /** Handle slots — {@code slots[i] == null} means handle i is free. */
    private byte[][] slots;
    /** Per-slot size in bytes. */
    private int[] sizes;
    /** Per-slot tag. */
    private int[] tags;
    /** Number of live handles currently allocated. */
    private int liveCount;
    /** Total backing-store capacity (sum of allocated byte[] sizes, capped at totalBytes). */
    private int totalBytes;
    /** Current state. */
    private State state;

    public JvmMemoryPort()
    {
        this.state = State.UNINITIALIZED;
    }

    @Override
    public void init(final int heapSizeBytes)
    {
        if (state != State.UNINITIALIZED)
        {
            throw new MemoryException("init() called from state " + state
                + " — only valid from UNINITIALIZED");
        }
        if (heapSizeBytes <= 0)
        {
            throw new MemoryException("init() heapSizeBytes must be > 0, got "
                + heapSizeBytes);
        }

        this.totalBytes = heapSizeBytes;
        final int initialCapacity = 64;
        this.slots = new byte[initialCapacity][];
        this.sizes = new int[initialCapacity];
        this.tags  = new int[initialCapacity];
        this.liveCount = 0;
        this.state = State.READY;

        LOG.info("JvmMemoryPort initialized: capacity={} bytes, slot table={} entries",
            heapSizeBytes, initialCapacity);
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new MemoryException("shutdown() called from state SHUTDOWN — already terminal");
        }
        // Release all live allocations back to the GC
        if (slots != null)
        {
            for (int i = 0; i < slots.length; i++)
            {
                slots[i] = null;
            }
        }
        liveCount = 0;
        state = State.SHUTDOWN;
        LOG.info("JvmMemoryPort shut down");
    }

    @Override
    public void reset()
    {
        if (state != State.ACTIVE && state != State.READY)
        {
            throw new MemoryException("reset() called from state " + state
                + " — only valid from READY or ACTIVE");
        }
        if (slots != null)
        {
            for (int i = 0; i < slots.length; i++)
            {
                slots[i] = null;
                sizes[i] = 0;
                tags[i] = 0;
            }
        }
        liveCount = 0;
        state = State.READY;
        LOG.info("JvmMemoryPort reset (all allocations cleared)");
    }

    @Override
    public State state()
    {
        return state;
    }

    @Override
    public int allocate(final int sizeBytes, final int tag)
    {
        if (state != State.ACTIVE && state != State.READY)
        {
            throw new MemoryException("allocate() called from state " + state
                + " — only valid from READY or ACTIVE");
        }
        if (sizeBytes < 0)
        {
            throw new MemoryException("allocate() sizeBytes must be >= 0, got " + sizeBytes);
        }
        if (tag < 0 || tag > 255)
        {
            throw new MemoryException("allocate() tag must be in [0, 255], got " + tag);
        }

        // Bound check: would this push us over the configured capacity?
        final int currentAllocated = allocatedBytes();
        if (currentAllocated + sizeBytes > totalBytes)
        {
            LOG.error("JvmMemoryPort OOM: requested {} bytes, would exceed capacity {} (currently {} used)",
                sizeBytes, totalBytes, currentAllocated);
            return NULL_HANDLE;
        }

        // Find or grow a slot
        int handle = findFreeSlot();
        if (handle < 0)
        {
            // No free slot — grow
            handle = growSlots();
        }

        slots[handle] = new byte[sizeBytes];
        sizes[handle] = sizeBytes;
        tags[handle]  = tag;
        liveCount++;

        // First allocation flips us into ACTIVE
        if (state == State.READY)
        {
            state = State.ACTIVE;
        }
        return handle;
    }

    @Override
    public void free(final int handle)
    {
        if (state != State.ACTIVE)
        {
            throw new MemoryException("free() called from state " + state
                + " — only valid from ACTIVE");
        }
        if (handle < 0 || handle >= slots.length)
        {
            throw new MemoryException("free() handle out of range: " + handle);
        }
        if (slots[handle] == null)
        {
            throw new MemoryException("free() handle " + handle + " is not currently allocated");
        }

        slots[handle] = null;
        sizes[handle] = 0;
        tags[handle]  = 0;
        liveCount--;
    }

    @Override
    public int freeByTag(final int tag)
    {
        if (state != State.ACTIVE && state != State.READY)
        {
            throw new MemoryException("freeByTag() called from state " + state
                + " — only valid from READY or ACTIVE");
        }
        if (tag < 0 || tag > 255)
        {
            throw new MemoryException("freeByTag() tag must be in [0, 255], got " + tag);
        }

        int freed = 0;
        for (int i = 0; i < slots.length; i++)
        {
            if (slots[i] != null && tags[i] == tag)
            {
                slots[i] = null;
                sizes[i] = 0;
                tags[i]  = 0;
                liveCount--;
                freed++;
            }
        }
        LOG.debug("JvmMemoryPort freeByTag({}) freed {} allocations", tag, freed);
        return freed;
    }

    @Override
    public int totalBytes()
    {
        return totalBytes;
    }

    @Override
    public int allocatedBytes()
    {
        if (slots == null)
        {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < slots.length; i++)
        {
            if (slots[i] != null)
            {
                sum += sizes[i];
            }
        }
        return sum;
    }

    @Override
    public int freeBytes()
    {
        return totalBytes - allocatedBytes();
    }

    @Override
    public int maxAllocatable()
    {
        // JVM port has no per-allocation overhead
        return freeBytes();
    }

    @Override
    public int handleCount()
    {
        return liveCount;
    }

    @Override
    public int sizeOf(final int handle)
    {
        if (handle < 0 || handle >= slots.length || slots[handle] == null)
        {
            return 0;
        }
        return sizes[handle];
    }

    // ---- internals ----

    /** Linear scan for a free slot. O(n) — fine for our n < 10000 case. */
    private int findFreeSlot()
    {
        if (slots == null)
        {
            return -1;
        }
        for (int i = 0; i < slots.length; i++)
        {
            if (slots[i] == null)
            {
                return i;
            }
        }
        return -1;
    }

    /** Grows the slots array, returning the first new free slot index. */
    private int growSlots()
    {
        final int oldLen = slots.length;
        final int newLen = oldLen * 2;
        final byte[][] newSlots = new byte[newLen][];
        final int[] newSizes = new int[newLen];
        final int[] newTags  = new int[newLen];
        System.arraycopy(slots, 0, newSlots, 0, oldLen);
        System.arraycopy(sizes, 0, newSizes, 0, oldLen);
        System.arraycopy(tags,  0, newTags,  0, oldLen);
        slots = newSlots;
        sizes = newSizes;
        tags  = newTags;
        return oldLen;
    }
}
