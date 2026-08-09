/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory.adapter;

import com.openfps.engine.common.Constants;
import com.openfps.engine.memory.MemoryException;
import com.openfps.engine.memory.port.I_MemoryPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Zone-allocator memory port — bulk-free backend.
 *
 * Backed by a single pre-allocated {@code byte[]} of fixed size.
 * Allocation is a bump pointer; individual {@link #free(int)} is a no-op
 * for memory reclamation (this is the standard DOOM zone-allocator
 * tradeoff — see DOOM source z_zone.c). Bulk-free happens via
 * {@link #freeByTag(int)} or {@link #reset()}.
 *
 * To still detect double-free and invalid handles (per the "no silent
 * failures" rule), we maintain a parallel {@code boolean[] live} bitmap:
 * each allocation's slot is true while live, false after free. This costs
 * one bit per possible allocation (~256 KB for a 16 MB heap) and lets
 * {@link #free(int)} and {@link #sizeOf(int)} reject invalid handles.
 *
 * Use this backend when you need:
 *   - Truly bounded memory (no GC heap growth)
 *   - Deterministic allocation timing (no GC pauses)
 *   - Bulk-free on map change (TAG_GAME) with zero pause
 *   - P2P lockstep determinism
 *
 * Tradeoffs:
 *   - Individual free() is a no-op for memory (but still throws on
 *     invalid handles)
 *   - OOM happens fast and hard (vs JVM which can ask for more heap)
 *
 * The header stored before each allocation block is 8 bytes:
 *
 *   [size: int32][tag: int16][padding: int16][... payload bytes ...]
 *
 * Handle is the offset of the header within the heap byte[].
 */
public final class ZoneMemoryPort implements I_MemoryPort
{
    private static final Logger LOG = LoggerFactory.getLogger(ZoneMemoryPort.class);

    /** Header size in bytes — 4 (size) + 2 (tag) + 2 (padding for alignment). */
    private static final int HEADER_SIZE = 8;

    private byte[] heap;
    private int heapSize;
    private int used;
    private int liveCount;
    /** Bitmap of live allocations, indexed by slot (= handle / HEADER_SIZE). */
    private boolean[] live;
    private int maxSlots;
    private State state;

    public ZoneMemoryPort()
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

        if (heapSizeBytes < HEADER_SIZE * 4)
        {
            throw new MemoryException("init() heapSizeBytes must be at least "
                + (HEADER_SIZE * 4) + " (to allow header overhead), got " + heapSizeBytes);
        }

        this.heap = new byte[heapSizeBytes];

        this.heapSize = heapSizeBytes;

        this.used = 0;

        this.liveCount = 0;

        this.maxSlots = heapSizeBytes / HEADER_SIZE;

        this.live = new boolean[maxSlots];

        this.state = State.READY;

        LOG.info("ZoneMemoryPort initialized: heap={} bytes, slots={}",
            heapSizeBytes, maxSlots);
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new MemoryException("shutdown() called from state SHUTDOWN — already terminal");
        }

        heap = null;

        live = null;

        used = 0;

        liveCount = 0;

        state = State.SHUTDOWN;

        LOG.info("ZoneMemoryPort shut down");
    }

    @Override
    public void reset()
    {
        if (state != State.ACTIVE && state != State.READY)
        {
            throw new MemoryException("reset() called from state " + state
                + " — only valid from READY or ACTIVE");
        }

        if (heap == null)
        {
            throw new MemoryException("reset() called with null heap — was init() ever called?");
        }

        // Wipe all memory and reset the bump pointer
        Arrays.fill(heap, (byte) 0);

        Arrays.fill(live, false);

        used = 0;

        liveCount = 0;

        state = State.READY;

        LOG.info("ZoneMemoryPort reset (all allocations cleared)");
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

        final int alignedPayload = align(sizeBytes);

        final int totalNeeded    = HEADER_SIZE + alignedPayload;

        if (used + totalNeeded > heapSize)
        {
            LOG.error("ZoneMemoryPort OOM: requested {} bytes, would exceed heap {} (currently {} used)",
                totalNeeded, heapSize, used);

            return NULL_HANDLE;
        }

        final int headerOffset = used;

        final int slot = headerOffset / HEADER_SIZE;

        // Write header
        writeHeader(headerOffset, sizeBytes, tag);

        used += totalNeeded;

        live[slot] = true;

        liveCount++;

        if (state == State.READY)
        {
            state = State.ACTIVE;
        }

        return headerOffset;
    }

    @Override
    public void free(final int handle)
    {
        if (state != State.ACTIVE)
        {
            throw new MemoryException("free() called from state " + state
                + " — only valid from ACTIVE");
        }

        if (handle < 0 || handle >= used)
        {
            throw new MemoryException("free() handle out of range: " + handle);
        }

        if (handle % HEADER_SIZE != 0)
        {
            throw new MemoryException("free() handle " + handle + " is not aligned to HEADER_SIZE");
        }

        final int slot = handle / HEADER_SIZE;

        if (slot >= maxSlots || !live[slot])
        {
            throw new MemoryException("free() handle " + handle + " is not currently allocated");
        }

        // Mark slot free. We don't reclaim the bytes — bulk-free only.
        live[slot] = false;

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

        if (heap == null)
        {
            return 0;
        }

        int freed = 0;

        int offset = 0;

        while (offset < used)
        {
            final int blockSize = readSize(offset);

            final int blockTag  = readTag(offset);

            final int alignedPayload = align(blockSize);

            final int totalBlock = HEADER_SIZE + alignedPayload;

            final int slot = offset / HEADER_SIZE;

            if (slot < maxSlots && live[slot] && blockTag == tag)
            {
                live[slot] = false;

                liveCount--;

                freed++;
            }

            offset += totalBlock;
        }

        LOG.debug("ZoneMemoryPort freeByTag({}) freed {} allocations (full reclaim on reset)",
            tag, freed);

        return freed;
    }

    @Override
    public int totalBytes()
    {
        return heapSize;
    }

    @Override
    public int allocatedBytes()
    {
        return used;
    }

    @Override
    public int freeBytes()
    {
        return heapSize - used;
    }

    @Override
    public int maxAllocatable()
    {
        // Zone port needs HEADER_SIZE bytes of overhead per allocation,
        // plus alignment padding. The conservative answer is the larger
        // of zero and (free bytes - header size). If free space is less
        // than a header, no payload can fit.
        final int free = heapSize - used;

        final int maxPayload = free - HEADER_SIZE;

        if (maxPayload < 0)
        {
            return 0;
        }

        return maxPayload;
    }

    @Override
    public int handleCount()
    {
        return liveCount;
    }

    @Override
    public int sizeOf(final int handle)
    {
        if (handle < 0 || handle >= used || heap == null)
        {
            return 0;
        }

        if (handle % HEADER_SIZE != 0)
        {
            return 0;
        }

        final int slot = handle / HEADER_SIZE;

        if (slot >= maxSlots || !live[slot])
        {
            return 0;
        }

        return readSize(handle);
    }

    // ---- header I/O (manual, no NIO ByteBuffer) ----

    private void writeHeader(final int offset, final int size, final int tag)
    {
        // size as big-endian int32 at offset+0
        heap[offset]     = (byte) ((size >>> 24) & 0xFF);

        heap[offset + 1] = (byte) ((size >>> 16) & 0xFF);

        heap[offset + 2] = (byte) ((size >>>  8) & 0xFF);

        heap[offset + 3] = (byte) (size & 0xFF);

        // tag as big-endian int16 at offset+4
        heap[offset + 4] = (byte) ((tag >>> 8) & 0xFF);

        heap[offset + 5] = (byte) (tag & 0xFF);
        // padding at offset+6/+7 (already zero from reset or default)
    }

    private int readSize(final int offset)
    {
        return ((heap[offset]     & 0xFF) << 24)
             | ((heap[offset + 1] & 0xFF) << 16)
             | ((heap[offset + 2] & 0xFF) <<  8)
             |  (heap[offset + 3] & 0xFF);
    }

    private int readTag(final int offset)
    {
        return ((heap[offset + 4] & 0xFF) << 8)
             |  (heap[offset + 5] & 0xFF);
    }

    private static int align(final int n)
    {
        return (n + Constants.ZONE_ALIGN - 1) & ~(Constants.ZONE_ALIGN - 1);
    }
}
