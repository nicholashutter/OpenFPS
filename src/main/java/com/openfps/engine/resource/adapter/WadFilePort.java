/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.common.Constants;
import com.openfps.engine.memory.factory.MemoryPortFactory;
import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.resource.WadException;
import com.openfps.engine.resource.port.I_WadPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W_ Real implementation of I_WadPort, backed by a {@link WadReader} and a
 * {@link LumpCache}.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * Lifecycle: {@code init()} arms the memory port, {@code open()} parses a
 * container, {@code close()} drops it, {@code shutdown()} tears the memory
 * port down. Opening a second WAD implicitly closes the first.
 *
 * How the port's two read paths map onto the cache's reference counting:
 *
 *   readLump()      makes the lump resident WITHOUT pinning it, so an
 *                   ordinary read stays a candidate for eviction. The
 *                   returned array belongs to the caller and stays valid
 *                   even if the cache later evicts the entry.
 *   precacheLump()  pins the lump — it will not be evicted to make room
 *                   for anything else.
 *   releaseLump()   the matching unpin. The lump is evicted the moment
 *                   the last reference goes.
 *   flushCache()    drops everything, pinned or not.
 *
 * {@code releaseLump} and {@code openInMemory} are additions beyond
 * {@link I_WadPort}: the interface has no unpin operation and no way to
 * open a container that is already in memory (a PWAD unpacked from an
 * archive, or a synthesized WAD in a test). Callers holding the interface
 * type are unaffected.
 *
 * This class is NOT thread-safe. The resource subsystem is driven from the
 * map-load path, which is single-threaded by construction.
 */
public final class WadFilePort implements I_WadPort
{
    private static final Logger LOG = LoggerFactory.getLogger(WadFilePort.class);

    /** Returned by {@link #lumpCount()} when no WAD is open. */
    private static final int NO_WAD_OPEN = -1;

    /** Memory port owning the lump cache budget. */
    private final I_MemoryPort memory;

    /** Cache budget in bytes. */
    private final int budgetBytes;

    /** Open container, or null. MUTABLE: set by open(), cleared by close(). */
    private WadReader reader;

    /** Cache over the open container, or null. MUTABLE: tracks reader. */
    private LumpCache cache;

    /** True between init() and shutdown(). MUTABLE: lifecycle flag. */
    private boolean initialized;

    /**
     * Creates a port with the engine's default zone budget, backed by the
     * JVM memory port from {@link MemoryPortFactory}.
     */
    public WadFilePort()
    {
        this(MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE), Constants.ZONE_HEAP_SIZE);
    }

    /**
     * Creates a port over a caller-supplied memory port. The port is
     * initialized by {@link #init()} if it has not been already, and is
     * shut down by {@link #shutdown()}.
     *
     * @param memory memory port that will own the cache budget
     * @param budgetBytes maximum resident lump bytes; must be positive
     */
    public WadFilePort(final I_MemoryPort memory, final int budgetBytes)
    {
        if (memory == null)
        {
            throw new WadException("WadFilePort requires an I_MemoryPort");
        }
        if (budgetBytes <= 0)
        {
            throw new WadException("WadFilePort budget must be > 0, got " + budgetBytes);
        }
        this.memory = memory;
        this.budgetBytes = budgetBytes;
    }

    @Override
    public void init()
    {
        if (initialized)
        {
            LOG.warn("WadFilePort.init() called twice — ignoring");
            return;
        }
        if (memory.state() == I_MemoryPort.State.UNINITIALIZED)
        {
            memory.init(budgetBytes);
        }
        initialized = true;
        LOG.info("WadFilePort initialized: lump cache budget={} bytes", budgetBytes);
    }

    @Override
    public boolean open(final String path)
    {
        requireInitialized("open");
        close();
        try
        {
            return adopt(WadReader.fromFile(path), path);
        }
        catch (final WadException e)
        {
            LOG.error("Failed to open WAD '{}'", path, e);
            return false;
        }
    }

    /**
     * Opens a WAD that is already in memory. Same contract as
     * {@link #open(String)}, but the image is supplied directly rather
     * than read from the filesystem.
     *
     * @param wadBytes the complete container image
     * @param label a name used only for logging
     * @return true if the image parsed as a well-formed WAD
     */
    public boolean openInMemory(final byte[] wadBytes, final String label)
    {
        requireInitialized("openInMemory");
        close();
        try
        {
            return adopt(WadReader.fromBytes(wadBytes), label);
        }
        catch (final WadException e)
        {
            LOG.error("Failed to open in-memory WAD '{}'", label, e);
            return false;
        }
    }

    @Override
    public void close()
    {
        if (reader == null)
        {
            return;
        }
        cache.flush();
        cache = null;
        reader = null;
        LOG.debug("WadFilePort closed the open WAD");
    }

    @Override
    public byte[] readLump(final int lumpIndex)
    {
        if (reader == null)
        {
            LOG.warn("readLump({}) with no WAD open", lumpIndex);
            return null;
        }
        if (!reader.hasLump(lumpIndex))
        {
            LOG.debug("readLump({}) — index out of range [0, {})", lumpIndex, reader.lumpCount());
            return null;
        }
        return cache.load(lumpIndex);
    }

    @Override
    public byte[] readLump(final String lumpName)
    {
        if (reader == null)
        {
            LOG.warn("readLump('{}') with no WAD open", lumpName);
            return null;
        }
        final int index = reader.findLump(lumpName);
        if (index == WadReader.LUMP_NOT_FOUND)
        {
            LOG.debug("readLump('{}') — no such lump", lumpName);
            return null;
        }
        return cache.load(index);
    }

    @Override
    public int lumpCount()
    {
        if (reader == null)
        {
            return NO_WAD_OPEN;
        }
        return reader.lumpCount();
    }

    @Override
    public void precacheLump(final int lumpIndex)
    {
        if (reader == null)
        {
            LOG.warn("precacheLump({}) with no WAD open", lumpIndex);
            return;
        }
        if (!reader.hasLump(lumpIndex))
        {
            LOG.warn("precacheLump({}) — index out of range [0, {})",
                lumpIndex, reader.lumpCount());
            return;
        }
        cache.acquire(lumpIndex);
    }

    /**
     * Drops one reference taken by {@link #precacheLump(int)}. When the
     * last reference goes the lump is evicted and its memory is returned
     * to the memory port.
     *
     * @param lumpIndex the lump to unpin
     */
    public void releaseLump(final int lumpIndex)
    {
        if (reader == null)
        {
            LOG.warn("releaseLump({}) with no WAD open", lumpIndex);
            return;
        }
        if (!reader.hasLump(lumpIndex))
        {
            LOG.warn("releaseLump({}) — index out of range [0, {})",
                lumpIndex, reader.lumpCount());
            return;
        }
        cache.release(lumpIndex);
    }

    @Override
    public void flushCache()
    {
        if (cache == null)
        {
            return;
        }
        cache.flush();
    }

    @Override
    public void shutdown()
    {
        close();
        if (memory.state() != I_MemoryPort.State.SHUTDOWN)
        {
            memory.shutdown();
        }
        initialized = false;
        LOG.info("WadFilePort shut down");
    }

    /**
     * Returns the kind of the open container, or null when none is open.
     */
    public WadReader.WadType wadType()
    {
        if (reader == null)
        {
            return null;
        }
        return reader.wadType();
    }

    /**
     * Returns the index of a named lump, or {@link WadReader#LUMP_NOT_FOUND}
     * when the name is absent or no WAD is open.
     *
     * @param lumpName up to 8 characters, case-insensitive
     * @return the lump index or the not-found sentinel
     */
    public int findLump(final String lumpName)
    {
        if (reader == null)
        {
            return WadReader.LUMP_NOT_FOUND;
        }
        return reader.findLump(lumpName);
    }

    /**
     * Returns the number of resident lump bytes, or 0 when no WAD is open.
     * Diagnostic only.
     */
    public int cachedBytes()
    {
        if (cache == null)
        {
            return 0;
        }
        return cache.residentBytes();
    }

    /**
     * Returns the number of resident lumps, or 0 when no WAD is open.
     * Diagnostic only.
     */
    public int cachedLumps()
    {
        if (cache == null)
        {
            return 0;
        }
        return cache.residentCount();
    }

    // ---- internals ----

    // Installs a freshly parsed container and its cache.
    private boolean adopt(final WadReader newReader, final String label)
    {
        this.reader = newReader;
        this.cache = new LumpCache(newReader, memory, budgetBytes);
        LOG.info("Opened {} '{}': {} lumps, {} byte image",
            newReader.wadType(), label, newReader.lumpCount(), newReader.imageBytes());
        return true;
    }

    // Guards operations that need an armed memory port.
    private void requireInitialized(final String operation)
    {
        if (!initialized)
        {
            throw new WadException("WadFilePort." + operation + "() called before init()");
        }
    }
}
