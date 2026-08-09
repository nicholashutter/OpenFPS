/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.factory;

import com.openfps.engine.memory.adapter.JvmMemoryPort;
import com.openfps.engine.memory.adapter.ZoneMemoryPort;
import com.openfps.engine.memory.port.I_MemoryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System-level selector for the memory backend.
 *
 * The rest of the engine never instantiates a memory port directly. It
 * asks this factory for one. The choice is made once, at engine boot,
 * based on the desired tradeoff:
 *
 *   - {@link #createJvm(int)}         — default. Bounded by capacity but
 *                                       allocates from the JVM heap, so GC
 *                                       still runs. Best for development
 *                                       and most production code.
 *
 *   - {@link #createZone(int)}        — bulk-free, deterministic, truly
 *                                       bounded. Best for entity pools
 *                                       and map data that needs to be
 *                                       freed en masse on map change.
 *
 *   - {@link #createSlab(int, int)}   — Phase 2+. Fixed-size slab for
 *                                       hot-path object pools. Returns
 *                                       a zone-backed port pre-partitioned
 *                                       into equal-sized blocks.
 *
 * If a caller later wants to know which backend it's using, it can
 * call {@link I_MemoryPort#getClass()}, but that should be a rare
 * diagnostic.
 */
public final class MemoryPortFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(MemoryPortFactory.class);

    private MemoryPortFactory()
    {
        // utility class
    }

    /**
     * Creates a JVM-heap backed memory port.
     * Allocations use {@code new byte[size]}; the tracking array only
     * records the size and tag for free() bookkeeping.
     *
     * @param heapSizeBytes capacity to enforce
     * @return a fresh, uninitialized port (call init() to use)
     */
    public static I_MemoryPort createJvm(final int heapSizeBytes)
    {
        LOG.info("MemoryPortFactory: selecting JvmMemoryPort backend (capacity={} bytes)",
            heapSizeBytes);

        return new JvmMemoryPort();
    }

    /**
     * Creates a zone-allocator backed memory port.
     * Allocations use a single pre-reserved byte[]; individual free is
     * a no-op; bulk-free by tag is the intended workflow.
     *
     * @param heapSizeBytes capacity of the zone
     * @return a fresh, uninitialized port (call init() to use)
     */
    public static I_MemoryPort createZone(final int heapSizeBytes)
    {
        LOG.info("MemoryPortFactory: selecting ZoneMemoryPort backend (heap={} bytes)",
            heapSizeBytes);

        return new ZoneMemoryPort();
    }

    /**
     * NOT IMPLEMENTED — always throws. Planned Phase 2 feature: a
     * zone-allocator backed port pre-partitioned into fixed
     * {@code blockSizeBytes} blocks, giving O(1) allocate and O(1) free with
     * zero fragmentation, for entity pools.
     *
     * <p>This threw nothing until now. It logged "slab-mode", discarded
     * <b>both</b> arguments — {@code ZoneMemoryPort} takes none — and returned
     * an ordinary unpartitioned bump allocator, while the Javadoc above it
     * promised blocks of exactly {@code blockSizeBytes}. A caller got a
     * plausible object that did not honour the contract it had just read, with
     * nothing at the call site to notice. That is the same silent-wrong-answer
     * shape as the 1024-entry trig table described in {@code common/README.md},
     * and it is refused here for the same reason: a method that cannot keep its
     * contract should say so rather than answer anyway.</p>
     *
     * <p>It has no callers and no tests today, so failing loudly costs nothing
     * and removes a trap for whoever writes the first entity pool.</p>
     *
     * @param heapSizeBytes total slab heap size
     * @param blockSizeBytes size of each slab block
     * @return never returns
     * @throws UnsupportedOperationException always
     */
    public static I_MemoryPort createSlab(final int heapSizeBytes, final int blockSizeBytes)
    {
        throw new UnsupportedOperationException(
            "createSlab(" + heapSizeBytes + ", " + blockSizeBytes + ") is a Phase 2 feature "
            + "and is not implemented. Use createZone(int) for a bump allocator, or implement "
            + "block partitioning in ZoneMemoryPort first — see memory/README.md.");
    }
}
