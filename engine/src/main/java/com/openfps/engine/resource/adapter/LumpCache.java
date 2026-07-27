/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.resource.WadException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W_ Demand-loaded, reference-counted cache of WAD lump payloads.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * Behaviour:
 *   - Lumps are loaded on first touch, never up front.
 *   - {@link #acquire(int)} pins a lump (reference count + 1).
 *   - {@link #release(int)} unpins it, and the lump is evicted the moment
 *     its reference count reaches zero. That is the "evict on release"
 *     contract: pinned means resident, unpinned means gone.
 *   - {@link #load(int)} is the unpinned read path — it makes the lump
 *     resident and returns it, but leaves the reference count at zero so
 *     the entry stays a candidate for eviction.
 *   - The cache is bounded by a byte budget. Making room evicts the
 *     least-recently-used unpinned entry first; if every resident lump is
 *     pinned and the budget still cannot be met, the load fails loudly.
 *
 * ====================================================================
 *  THE MEMORY-PORT TENSION (read this before "fixing" it)
 * ====================================================================
 *
 *  STYLE.md forbids {@code new byte[]} outside a memory-port adapter and
 *  routes every allocation through {@code I_MemoryPort}. But
 *  {@code I_MemoryPort} deliberately hands back opaque int handles and
 *  exposes NO read or write operation — the whole point of the handle
 *  abstraction is that the client never dereferences it. A lump has to be
 *  parsed and handed to the render, audio and gameplay subsystems as
 *  bytes, so it cannot live behind a handle today.
 *
 *  The compromise implemented here: the memory port owns the BUDGET and
 *  the LIFECYCLE, and the byte buffer is a slice of the WAD file image.
 *  Every resident lump holds a matching {@code allocate(size, TAG_CACHE)}
 *  handle, so the port's capacity check, its tag accounting and its
 *  {@code allocatedBytes()} view all see the cache exactly as they would
 *  if the bytes lived inside the port. Eviction calls {@code free(handle)}.
 *  If the port refuses, the load fails. The bytes themselves come from
 *  {@link WadReader#sliceLump(int)} — the single buffer site in the
 *  subsystem, documented there.
 *
 *  The one honest gap: a buffer returned by {@link #load(int)} stays alive
 *  in the caller's hands after eviction, so the port's accounting can
 *  briefly under-count real resident memory. Callers that need the
 *  accounting to be exact must use {@link #acquire(int)} /
 *  {@link #release(int)} instead. If {@code I_MemoryPort} ever grows a
 *  read/write API, this class and {@code WadReader.sliceLump} are the only
 *  two places that need to change.
 */
public final class LumpCache
{
    private static final Logger LOG = LoggerFactory.getLogger(LumpCache.class);

    /** Sentinel index meaning "no eviction candidate". */
    private static final int NO_VICTIM = -1;

    /** The WAD this cache reads through. */
    private final WadReader reader;

    /** Memory port owning the cache budget and per-lump handles. */
    private final I_MemoryPort memory;

    /** Maximum resident payload bytes. */
    private final int budgetBytes;

    /**
     * Resident payloads by lump index; null means not resident.
     * An array of references, not a byte buffer — the payloads themselves
     * come from {@link WadReader#sliceLump(int)}.
     * MUTABLE: filled on load, nulled on evict.
     */
    private final byte[][] buffers;

    /** Reference count per lump. MUTABLE: updated by acquire/release. */
    private final int[] refCounts;

    /** Memory-port handle per lump. MUTABLE: valid only while resident. */
    private final int[] handles;

    /** Last-use stamp per lump, for LRU eviction. MUTABLE. */
    private final long[] useStamps;

    /** Resident payload bytes. MUTABLE: tracks load and evict. */
    private int residentBytes;

    /** Number of resident lumps. MUTABLE: tracks load and evict. */
    private int residentCount;

    /** Monotonic source for {@link #useStamps}. MUTABLE: incremented on touch. */
    private long useClock;

    /**
     * Creates a cache over an open WAD.
     *
     * @param reader the WAD to read lumps from
     * @param memory memory port that owns the budget and per-lump handles
     * @param budgetBytes maximum resident payload bytes; must be positive
     */
    public LumpCache(final WadReader reader, final I_MemoryPort memory, final int budgetBytes)
    {
        if (reader == null)
        {
            throw new WadException("LumpCache requires a WadReader");
        }
        if (memory == null)
        {
            throw new WadException("LumpCache requires an I_MemoryPort");
        }
        if (budgetBytes <= 0)
        {
            throw new WadException("LumpCache budget must be > 0, got " + budgetBytes);
        }

        final int count = reader.lumpCount();
        this.reader = reader;
        this.memory = memory;
        this.budgetBytes = budgetBytes;
        this.buffers = new byte[count][];
        this.refCounts = new int[count];
        this.handles = new int[count];
        this.useStamps = new long[count];
        for (int i = 0; i < count; i++)
        {
            this.handles[i] = I_MemoryPort.NULL_HANDLE;
        }
    }

    /**
     * Makes a lump resident and returns it WITHOUT taking a reference.
     * The entry stays evictable, which is what an ordinary read wants.
     *
     * @param index zero-based lump index
     * @return the lump payload; callers must treat it as read-only
     * @throws WadException if the index is out of range or the load cannot be satisfied
     */
    public byte[] load(final int index)
    {
        return ensureResident(index);
    }

    /**
     * Makes a lump resident, pins it, and returns it. A pinned lump is
     * never chosen for eviction. Pair every call with {@link #release(int)}.
     *
     * @param index zero-based lump index
     * @return the lump payload; callers must treat it as read-only
     * @throws WadException if the index is out of range or the load cannot be satisfied
     */
    public byte[] acquire(final int index)
    {
        final byte[] payload = ensureResident(index);
        refCounts[index]++;
        return payload;
    }

    /**
     * Drops one reference to a lump. When the last reference goes, the
     * lump is evicted immediately and its memory-port handle is freed.
     *
     * Releasing a lump that is not resident, or whose reference count is
     * already zero, is a caller bug: it is logged and ignored rather than
     * thrown, because a shutdown path unwinding after an earlier failure
     * must not be derailed by it.
     *
     * @param index zero-based lump index
     * @throws WadException if the index is out of range
     */
    public void release(final int index)
    {
        checkIndex(index);
        if (buffers[index] == null)
        {
            LOG.warn("LumpCache.release({}) — lump '{}' is not resident",
                index, reader.lumpName(index));
            return;
        }
        if (refCounts[index] == 0)
        {
            LOG.warn("LumpCache.release({}) — lump '{}' has no outstanding references",
                index, reader.lumpName(index));
            return;
        }
        refCounts[index]--;
        if (refCounts[index] == 0)
        {
            evict(index);
        }
    }

    /**
     * Evicts every resident lump regardless of reference count and frees
     * all memory-port handles. Used on map change and on WAD close.
     */
    public void flush()
    {
        final int before = residentCount;
        for (int i = 0; i < buffers.length; i++)
        {
            if (buffers[i] != null)
            {
                refCounts[i] = 0;
                evict(i);
            }
        }
        LOG.debug("LumpCache flushed {} resident lumps", before);
    }

    /**
     * Returns true when the lump is currently resident.
     *
     * @param index zero-based lump index
     * @return residency
     */
    public boolean isResident(final int index)
    {
        checkIndex(index);
        return buffers[index] != null;
    }

    /**
     * Returns the outstanding reference count for a lump; zero when the
     * lump is unpinned or not resident.
     *
     * @param index zero-based lump index
     * @return reference count
     */
    public int refCount(final int index)
    {
        checkIndex(index);
        return refCounts[index];
    }

    /** Returns the number of resident payload bytes. */
    public int residentBytes()
    {
        return residentBytes;
    }

    /** Returns the number of resident lumps. */
    public int residentCount()
    {
        return residentCount;
    }

    /** Returns the configured byte budget. */
    public int budgetBytes()
    {
        return budgetBytes;
    }

    // ---- internals ----

    // Loads the lump if needed, stamps it as most-recently-used, returns it.
    private byte[] ensureResident(final int index)
    {
        checkIndex(index);
        if (buffers[index] != null)
        {
            touch(index);
            return buffers[index];
        }

        final int size = reader.lumpSize(index);
        if (size > budgetBytes)
        {
            throw new WadException("Lump '" + reader.lumpName(index) + "' is " + size
                + " bytes, larger than the whole cache budget of " + budgetBytes);
        }
        makeRoom(size);

        final int handle = memory.allocate(size, I_MemoryPort.TAG_CACHE);
        if (handle == I_MemoryPort.NULL_HANDLE)
        {
            throw new WadException("Memory port refused " + size + " bytes for lump '"
                + reader.lumpName(index) + "'");
        }

        final byte[] payload = reader.sliceLump(index);
        buffers[index] = payload;
        handles[index] = handle;
        residentBytes += size;
        residentCount++;
        touch(index);

        LOG.debug("LumpCache loaded lump {} '{}' ({} bytes); resident {}/{} bytes in {} lumps",
            index, reader.lumpName(index), size, residentBytes, budgetBytes, residentCount);
        return payload;
    }

    // Evicts unpinned LRU entries until the incoming payload fits.
    private void makeRoom(final int incomingBytes)
    {
        while (residentBytes + incomingBytes > budgetBytes)
        {
            final int victim = findVictim();
            if (victim == NO_VICTIM)
            {
                throw new WadException("LumpCache budget exhausted: need " + incomingBytes
                    + " bytes, " + residentBytes + "/" + budgetBytes
                    + " resident and every resident lump is pinned");
            }
            LOG.debug("LumpCache evicting lump {} '{}' to make room for {} bytes",
                victim, reader.lumpName(victim), incomingBytes);
            evict(victim);
        }
    }

    // Least-recently-used resident lump with no outstanding references.
    private int findVictim()
    {
        int victim = NO_VICTIM;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < buffers.length; i++)
        {
            if (buffers[i] != null && refCounts[i] == 0 && useStamps[i] < oldest)
            {
                oldest = useStamps[i];
                victim = i;
            }
        }
        return victim;
    }

    // Drops a resident entry and returns its bytes to the memory port.
    private void evict(final int index)
    {
        memory.free(handles[index]);
        residentBytes -= reader.lumpSize(index);
        residentCount--;
        buffers[index] = null;
        handles[index] = I_MemoryPort.NULL_HANDLE;
        refCounts[index] = 0;
        useStamps[index] = 0L;
    }

    // Marks a lump as most recently used.
    private void touch(final int index)
    {
        useClock++;
        useStamps[index] = useClock;
    }

    // Rejects an out-of-range lump index loudly rather than returning null.
    private void checkIndex(final int index)
    {
        if (!reader.hasLump(index))
        {
            throw new WadException("Lump index " + index + " out of range [0, "
                + reader.lumpCount() + ")");
        }
    }
}
