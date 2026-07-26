/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.port;

/**
 * W_ Port interface — WAD file resource loading.
 * Reads lumps from .wad files and caches them by name and index.
 *
 * ====================================================================
 *  WAD FILE FORMAT (Phase 2+ — references below)
 * ====================================================================
 *
 *  Full format spec in src/main/java/com/openfps/engine/resource/README.md.
 *  Summary:
 *
 *  HEADER (12 bytes, little-endian):
 *      offset 0:  int32  magic         "IWAD" (main) or "PWAD" (patch)
 *      offset 4:  int32  lumpCount    number of lumps
 *      offset 8:  int32  dirOffset    byte offset of lump directory
 *
 *  DIRECTORY ENTRY (16 bytes per lump, N entries):
 *      offset 0:  int32  filePos      byte offset of lump data
 *      offset 4:  int32  size         size in bytes
 *      offset 8:  char[8] name        uppercase, null-padded
 *
 *  Each lump is a contiguous block of bytes at filePos of length size.
 *  Lumps can be anything: map data, textures, sound bytes, MIDI music.
 *
 *  PATCH IMAGE FORMAT (used for wall textures and sprites):
 *      Header:
 *        uint16 width
 *        uint16 height
 *        int16  leftOffset
 *        int16  topOffset
 *        uint32[height] columnOffsets
 *      Per column (top to bottom):
 *        uint8 topDelta
 *        repeat:
 *          uint8 length     (0 = end of column marker)
 *          if length == 0: break
 *          uint8 pad1
 *          uint8[length] pixels
 *          uint8 pad2
 *
 *  FLAT IMAGE FORMAT (used for floors/ceilings):
 *      byte[4096] pixels
 *      Width = height = 64. Pixel (x, y) at byteOffset = y * 64 + x.
 *      Each byte is a palette index.
 *
 *  Source:
 *    - http://doom.wikia.com/wiki/WAD
 *    - http://doom.wikia.com/wiki/Picture_format
 *    - DOOM source r_patch.c, r_flat.c
 *
 *  Endianness: ALL WAD fields are little-endian. Always read with
 *  ByteBuffer.order(LITTLE_ENDIAN) or manual bit shifts.
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
