/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.port;

/**
 * W_ Port interface — WAD file resource loading.
 * Reads lumps from .wad files and caches them by name and index.
 */
public interface I_WadPort
{
    /**
     * Opens a WAD file.
     *
     * @param path filesystem path to the .wad file
     * @return true if successfully opened
     */
    boolean open(final String path);

    /**
     * Closes the currently open WAD file.
     */
    void close();

    /**
     * Reads a lump by its integer index.
     * May trigger a cache load.
     *
     * @param lumpIndex zero-based lump index
     * @return lump bytes, or null if not found
     */
    byte[] readLump(final int lumpIndex);

    /**
     * Reads a lump by name. Searches the WAD's lump name table.
     *
     * @param lumpName up to 8-character lump name (padded with \0)
     * @return lump bytes, or null
     */
    byte[] readLump(final String lumpName);

    /**
     * Returns the total number of lumps in the open WAD.
     *
     * @return lump count, or -1 if no WAD is open
     */
    int lumpCount();

    /**
     * Pre-caches a lump into memory.
     *
     * @param lumpIndex the lump to cache
     */
    void precacheLump(final int lumpIndex);

    /**
     * Frees all cached lumps.
     */
    void flushCache();

    /**
     * Initializes the resource subsystem.
     */
    void init();

    /**
     * Shuts down the resource subsystem.
     */
    void shutdown();
}
