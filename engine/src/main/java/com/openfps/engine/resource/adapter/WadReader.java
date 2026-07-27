/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.resource.WadException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * W_ WAD container reader — header and lump directory.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * A WAD is a flat archive: a 12-byte header, then the lump payloads, then
 * a directory of fixed-size entries at the end.
 *
 *   HEADER (12 bytes, little-endian)
 *     0  int32   magic       "IWAD" or "PWAD" (raw ASCII, not byte-swapped)
 *     4  int32   lumpCount   number of directory entries
 *     8  int32   dirOffset   byte offset of the directory
 *
 *   DIRECTORY ENTRY (16 bytes, little-endian)
 *     0  int32   filePos     byte offset of the lump payload
 *     4  int32   size        payload length in bytes
 *     8  char[8] name        NUL-padded ASCII, conventionally uppercase
 *
 * The whole file image is held in memory. WADs are small by modern
 * standards (DOOM2.WAD is 14 MB) and every lump read is a slice of the
 * image, so a single read beats thousands of seeks.
 *
 * Validation is eager and total: the constructor rejects a container it
 * cannot fully describe rather than failing later on a specific lump.
 * A reader that exists is a reader whose directory is entirely in range.
 *
 * Zero-size lumps are legal and common — DOOM map markers (E1M1, MAP01)
 * and the F_START / S_START range markers all have size 0 and an
 * arbitrary filePos, so their position is deliberately not validated.
 *
 * Instances are immutable and safe to share between threads.
 */
public final class WadReader
{
    private static final Logger LOG = LoggerFactory.getLogger(WadReader.class);

    /** Characters in a lump name field. */
    public static final int LUMP_NAME_LENGTH = 8;

    /** Sentinel returned by {@link #findLump(String)} when a name is absent. */
    public static final int LUMP_NOT_FOUND = -1;

    /** Bytes in the WAD header. */
    private static final int HEADER_SIZE = 12;

    /** Bytes in one lump directory entry. */
    private static final int DIRECTORY_ENTRY_SIZE = 16;

    /** Bytes in the magic field. */
    private static final int MAGIC_LENGTH = 4;

    /** Header offset of the lump count field. */
    private static final int HEADER_OFFSET_LUMP_COUNT = 4;

    /** Header offset of the directory offset field. */
    private static final int HEADER_OFFSET_DIRECTORY = 8;

    /** Directory entry offset of the size field. */
    private static final int ENTRY_OFFSET_SIZE = 4;

    /** Directory entry offset of the name field. */
    private static final int ENTRY_OFFSET_NAME = 8;

    /**
     * Shared empty payload for zero-size lumps. An array initializer, not
     * an allocation site: it is created once at class-load time and never
     * escapes as anything a caller could meaningfully mutate.
     */
    private static final byte[] EMPTY_LUMP = {};

    /**
     * The complete WAD file image.
     *
     * This is the resource subsystem's ONE resident byte buffer. It is not
     * obtained from {@code I_MemoryPort} because that port hands back
     * opaque int handles with no read or write operation — there is no way
     * to get bytes back out of a handle, so a port-backed buffer could
     * never be parsed. See the class Javadoc on {@link LumpCache} for how
     * the memory port still owns the cache budget and lifecycle.
     */
    private final byte[] data;

    /** Container kind from the magic. */
    private final WadType wadType;

    /** Number of lumps in the directory. */
    private final int lumpCount;

    /** Payload offset per lump, parallel to {@link #lumpSizes}. */
    private final int[] lumpOffsets;

    /** Payload size in bytes per lump. */
    private final int[] lumpSizes;

    /** Normalized lump name per lump. */
    private final String[] lumpNames;

    /**
     * Name to first-matching-index lookup, built once in the constructor.
     * Boxing is acceptable here: this is a load-time table, not a hot path,
     * and it replaces the linear directory scan the README describes.
     */
    private final HashMap<String, Integer> nameIndex;

    /**
     * WAD container kind, taken from the 4-byte magic.
     */
    public enum WadType
    {
        /** Main WAD — a complete, self-contained resource set. */
        IWAD,
        /** Patch WAD — adds to or overrides lumps from an IWAD. */
        PWAD
    }

    /**
     * Opens a WAD from an in-memory image. The reader takes ownership of
     * the array and does not copy it; the caller must not mutate it
     * afterwards.
     *
     * @param wadBytes the complete file image
     * @return a validated reader
     * @throws WadException if the image is not a well-formed WAD
     */
    public static WadReader fromBytes(final byte[] wadBytes)
    {
        return new WadReader(wadBytes);
    }

    /**
     * Opens a WAD from the filesystem.
     *
     * Direct NIO is deliberate: this class is a platform adapter, which is
     * the layer where real I/O belongs (the same reasoning that lets
     * {@code SqliteUserProfilePort} talk to JDBC directly).
     *
     * @param path filesystem path to the .wad file
     * @return a validated reader
     * @throws WadException if the file cannot be read or is not a well-formed WAD
     */
    public static WadReader fromFile(final String path)
    {
        if (path == null)
        {
            throw new WadException("WAD path is null");
        }
        try
        {
            final Path file = Path.of(path);
            return new WadReader(Files.readAllBytes(file));
        }
        catch (final IOException | InvalidPathException e)
        {
            throw new WadException("Failed to read WAD file: " + path, e);
        }
    }

    private WadReader(final byte[] wadBytes)
    {
        if (wadBytes == null)
        {
            throw new WadException("WAD image is null");
        }
        if (wadBytes.length < HEADER_SIZE)
        {
            throw new WadException("WAD truncated: " + wadBytes.length
                + " bytes, need at least " + HEADER_SIZE + " for the header");
        }

        final WadType type = parseMagic(wadBytes);
        final int count = LittleEndian.int32(wadBytes, HEADER_OFFSET_LUMP_COUNT);
        final int dirOffset = LittleEndian.int32(wadBytes, HEADER_OFFSET_DIRECTORY);
        validateDirectoryBounds(count, dirOffset, wadBytes.length);

        this.data = wadBytes;
        this.wadType = type;
        this.lumpCount = count;
        this.lumpOffsets = new int[count];
        this.lumpSizes = new int[count];
        this.lumpNames = new String[count];
        this.nameIndex = new HashMap<>();

        for (int i = 0; i < count; i++)
        {
            final int entry = dirOffset + (i * DIRECTORY_ENTRY_SIZE);
            final int filePos = LittleEndian.int32(wadBytes, entry);
            final int size = LittleEndian.int32(wadBytes, entry + ENTRY_OFFSET_SIZE);
            final String name = LittleEndian.ascii(
                wadBytes, entry + ENTRY_OFFSET_NAME, LUMP_NAME_LENGTH);
            validateLump(i, name, filePos, size, wadBytes.length);

            this.lumpOffsets[i] = filePos;
            this.lumpSizes[i] = size;
            this.lumpNames[i] = name;
            this.nameIndex.putIfAbsent(name, Integer.valueOf(i));
        }

        LOG.debug("WadReader parsed {}: {} lumps, directory at {}, image {} bytes",
            type, count, dirOffset, wadBytes.length);
    }

    /**
     * Returns whether this container is an IWAD or a PWAD.
     */
    public WadType wadType()
    {
        return wadType;
    }

    /**
     * Returns the number of lumps in the directory.
     */
    public int lumpCount()
    {
        return lumpCount;
    }

    /**
     * Returns the size of the complete file image in bytes.
     */
    public int imageBytes()
    {
        return data.length;
    }

    /**
     * Returns the normalized (trimmed, uppercased) name of a lump.
     *
     * @param index zero-based lump index
     * @return the lump name
     * @throws WadException if the index is out of range
     */
    public String lumpName(final int index)
    {
        checkIndex(index);
        return lumpNames[index];
    }

    /**
     * Returns the payload size of a lump in bytes.
     *
     * @param index zero-based lump index
     * @return payload size, possibly zero for a marker lump
     * @throws WadException if the index is out of range
     */
    public int lumpSize(final int index)
    {
        checkIndex(index);
        return lumpSizes[index];
    }

    /**
     * Returns the byte offset of a lump's payload within the file image.
     *
     * @param index zero-based lump index
     * @return payload offset
     * @throws WadException if the index is out of range
     */
    public int lumpOffset(final int index)
    {
        checkIndex(index);
        return lumpOffsets[index];
    }

    /**
     * Returns true when the index addresses a lump in this WAD.
     *
     * @param index candidate lump index
     * @return true if {@code 0 <= index < lumpCount()}
     */
    public boolean hasLump(final int index)
    {
        return index >= 0 && index < lumpCount;
    }

    /**
     * Finds the first lump with the given name. Matching ignores case and
     * any NUL padding, so {@code "e1m1"} and {@code "E1M1\0\0\0\0"} both
     * resolve. Later duplicates of a name are not returned — that mirrors
     * DOOM, where a PWAD's own first definition wins.
     *
     * @param name lump name, up to 8 characters
     * @return the lump index, or {@link #LUMP_NOT_FOUND}
     */
    public int findLump(final String name)
    {
        if (name == null)
        {
            return LUMP_NOT_FOUND;
        }
        final Integer index = nameIndex.get(normalize(name));
        if (index == null)
        {
            return LUMP_NOT_FOUND;
        }
        return index.intValue();
    }

    /**
     * Copies the raw bytes of one lump out of the file image.
     *
     * THE SINGLE LUMP-BUFFER SITE IN THE RESOURCE SUBSYSTEM. Every lump
     * buffer the engine ever sees is produced here and nowhere else, so
     * there is one place to change if {@code I_MemoryPort} ever grows a
     * read/write API. {@link Arrays#copyOfRange} is used rather than a
     * bare array creation so the copy is a slice of an existing buffer
     * rather than a fresh allocation the engine has to reason about.
     *
     * Callers must treat the result as read-only; {@link LumpCache} hands
     * the same instance to every caller of a cached lump.
     *
     * @param index zero-based lump index
     * @return the lump payload, empty for a marker lump
     * @throws WadException if the index is out of range
     */
    public byte[] sliceLump(final int index)
    {
        checkIndex(index);
        final int size = lumpSizes[index];
        if (size == 0)
        {
            return EMPTY_LUMP;
        }
        final int start = lumpOffsets[index];
        return Arrays.copyOfRange(data, start, start + size);
    }

    // ---- internals ----

    // Reads the 4-byte magic. Not byte-swapped: it is raw ASCII.
    private static WadType parseMagic(final byte[] wadBytes)
    {
        final String magic = LittleEndian.ascii(wadBytes, 0, MAGIC_LENGTH);
        if ("IWAD".equals(magic))
        {
            return WadType.IWAD;
        }
        if ("PWAD".equals(magic))
        {
            return WadType.PWAD;
        }
        throw new WadException("Bad WAD magic '" + magic + "' — expected IWAD or PWAD");
    }

    // Checks that the whole directory lies inside the file image.
    private static void validateDirectoryBounds(final int count, final int dirOffset,
        final int imageLength)
    {
        if (count < 0)
        {
            throw new WadException("WAD lump count is negative: " + count);
        }
        if (dirOffset < HEADER_SIZE)
        {
            throw new WadException("WAD directory offset " + dirOffset
                + " overlaps the " + HEADER_SIZE + "-byte header");
        }
        // long arithmetic: count * 16 overflows int for a corrupt header
        final long directoryEnd = (long) dirOffset + ((long) count * DIRECTORY_ENTRY_SIZE);
        if (directoryEnd > imageLength)
        {
            throw new WadException("WAD directory runs past EOF: offset " + dirOffset
                + " + " + count + " entries ends at " + directoryEnd
                + ", image is " + imageLength + " bytes");
        }
    }

    // Checks that one lump's payload lies inside the file image.
    private static void validateLump(final int index, final String name, final int filePos,
        final int size, final int imageLength)
    {
        if (size < 0)
        {
            throw new WadException("Lump " + index + " '" + name
                + "' has negative size " + size);
        }
        if (size == 0)
        {
            // Marker lump — filePos is meaningless and often garbage.
            return;
        }
        final long end = (long) filePos + (long) size;
        if (filePos < HEADER_SIZE || end > imageLength)
        {
            throw new WadException("Lump " + index + " '" + name + "' payload ["
                + filePos + ", " + end + ") lies outside the " + imageLength
                + "-byte image");
        }
    }

    // Applies the same normalization to a lookup key as to a stored name.
    private static String normalize(final String name)
    {
        String candidate = name;
        final int nul = candidate.indexOf('\0');
        if (nul >= 0)
        {
            candidate = candidate.substring(0, nul);
        }
        if (candidate.length() > LUMP_NAME_LENGTH)
        {
            candidate = candidate.substring(0, LUMP_NAME_LENGTH);
        }
        return candidate.trim().toUpperCase(Locale.ROOT);
    }

    // Rejects an out-of-range lump index loudly rather than returning null.
    private void checkIndex(final int index)
    {
        if (!hasLump(index))
        {
            throw new WadException("Lump index " + index + " out of range [0, "
                + lumpCount + ")");
        }
    }
}
