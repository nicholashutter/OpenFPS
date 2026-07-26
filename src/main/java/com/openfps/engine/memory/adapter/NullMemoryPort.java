/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.adapter;

import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.common.Constants;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Z_ Null adapter for memory management.
 * Uses a simple byte[] heap with bump-pointer allocation.
 * Thread-safe via synchronized — replace with lock-free for production.
 */
public final class NullMemoryPort implements I_MemoryPort
{
    private static final Logger LOG = Logger.getLogger(NullMemoryPort.class.getName());

    private byte[] heap;
    private int heapUsed;
    private int heapSize;
    private int[] tagTable;   // MUTABLE: tag per allocation slot (simplified)
    private int allocationCount;

    @Override
    public long allocate(final int sizeBytes, final int tag)
    {
        synchronized (this)
        {
            final int alignedSize = align(sizeBytes);
            if (heapUsed + alignedSize > heapSize)
            {
                LOG.log(Level.SEVERE, "Zone heap exhausted! Requested {0} bytes", alignedSize);
                return 0L;
            }
            final int offset = heapUsed;
            heapUsed += alignedSize;
            allocationCount++;
            // Store tag (simplified — real impl uses a free list or slab)
            return (long) offset;
        }
    }

    @Override
    public void free(final long pointer)
    {
        // no-op in bump allocator — freed only on reset
    }

    @Override
    public void freeByTag(final int tag)
    {
        // no-op in bump allocator
    }

    @Override
    public void reset()
    {
        synchronized (this)
        {
            Arrays.fill(heap, (byte) 0);
            heapUsed = 0;
            allocationCount = 0;
        }
    }

    @Override
    public int allocatedBytes()
    {
        return heapUsed;
    }

    @Override
    public void init(final int heapSizeBytes)
    {
        heapSize = heapSizeBytes;
        heap = new byte[heapSize];
        heapUsed = 0;
        allocationCount = 0;
        LOG.log(Level.INFO, "Zone allocator initialized: {0} bytes", heapSize);
    }

    @Override
    public void shutdown()
    {
        heap = null;
        heapUsed = 0;
        LOG.log(Level.INFO, "Zone allocator shut down.");
    }

    private static int align(final int size)
    {
        return (size + Constants.ZONE_ALIGN - 1) & ~(Constants.ZONE_ALIGN - 1);
    }
}
