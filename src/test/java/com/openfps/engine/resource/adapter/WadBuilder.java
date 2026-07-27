/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Synthesizes WAD file images in memory for the resource tests.
 *
 * No test in this package needs a .wad on disk: every container the tests
 * exercise is built here, byte by byte. The builder deliberately uses
 * {@link ByteBuffer} with {@link ByteOrder#LITTLE_ENDIAN} rather than the
 * production shift helpers, so a test failure means the two independent
 * implementations disagree rather than that one bug is being checked
 * against itself.
 *
 * The header fields can be overridden independently of the real layout
 * ({@link #declaredLumpCount(int)}, {@link #declaredDirectoryOffset(int)})
 * so corrupt containers can be produced on purpose.
 */
final class WadBuilder
{
    /** Bytes in the WAD header. */
    static final int HEADER_SIZE = 12;

    /** Bytes in one directory entry. */
    static final int ENTRY_SIZE = 16;

    /** Characters in a lump name field. */
    static final int NAME_LENGTH = 8;

    /** Bytes in the magic field. */
    private static final int MAGIC_LENGTH = 4;

    /** Low byte mask. */
    private static final int BYTE_MASK = 0xFF;

    /** Lump names, parallel to payloads. MUTABLE: appended by lump(). */
    private final ArrayList<String> names = new ArrayList<>();

    /** Lump payloads, parallel to names. MUTABLE: appended by lump(). */
    private final ArrayList<byte[]> payloads = new ArrayList<>();

    /** Magic to write. MUTABLE: overridden by magic(). */
    private String magic = "IWAD";

    /** Header lump count, or -1 to use the real one. MUTABLE. */
    private int declaredLumpCount = -1;

    /** Header directory offset, or -1 to use the real one. MUTABLE. */
    private int declaredDirectoryOffset = -1;

    /** Starts a builder for a main WAD. */
    static WadBuilder iwad()
    {
        return new WadBuilder();
    }

    /** Starts a builder for a patch WAD. */
    static WadBuilder pwad()
    {
        return new WadBuilder().magic("PWAD");
    }

    /** Sets the 4-character magic, valid or otherwise. */
    WadBuilder magic(final String value)
    {
        this.magic = value;
        return this;
    }

    /** Appends a lump with a payload. */
    WadBuilder lump(final String name, final byte[] payload)
    {
        names.add(name);
        payloads.add(payload);
        return this;
    }

    /** Appends a zero-size marker lump, as used for E1M1 or F_START. */
    WadBuilder marker(final String name)
    {
        return lump(name, new byte[0]);
    }

    /** Writes a lump count in the header that differs from reality. */
    WadBuilder declaredLumpCount(final int value)
    {
        this.declaredLumpCount = value;
        return this;
    }

    /** Writes a directory offset in the header that differs from reality. */
    WadBuilder declaredDirectoryOffset(final int value)
    {
        this.declaredDirectoryOffset = value;
        return this;
    }

    /** Assembles the complete file image. */
    byte[] build()
    {
        int dataSize = 0;
        for (final byte[] payload : payloads)
        {
            dataSize += payload.length;
        }
        final int directoryStart = HEADER_SIZE + dataSize;
        final int total = directoryStart + (payloads.size() * ENTRY_SIZE);
        final ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);

        buf.put(padded(magic, MAGIC_LENGTH));
        buf.putInt(headerLumpCount());
        buf.putInt(headerDirectoryOffset(directoryStart));

        final int[] offsets = new int[payloads.size()];
        for (int i = 0; i < payloads.size(); i++)
        {
            offsets[i] = buf.position();
            buf.put(payloads.get(i));
        }
        for (int i = 0; i < payloads.size(); i++)
        {
            buf.putInt(offsets[i]);
            buf.putInt(payloads.get(i).length);
            buf.put(padded(names.get(i), NAME_LENGTH));
        }
        return buf.array();
    }

    /** Packs signed 16-bit values little-endian — the map lump record encoding. */
    static byte[] words(final int... values)
    {
        final ByteBuffer buf = ByteBuffer.allocate(values.length * 2)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (final int value : values)
        {
            buf.putShort((short) value);
        }
        return buf.array();
    }

    /** Packs raw bytes, each value truncated to 8 bits. */
    static byte[] bytes(final int... values)
    {
        final byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++)
        {
            out[i] = (byte) (values[i] & BYTE_MASK);
        }
        return out;
    }

    /** Concatenates byte blocks in order. */
    static byte[] concat(final byte[]... blocks)
    {
        int total = 0;
        for (final byte[] block : blocks)
        {
            total += block.length;
        }
        final byte[] out = new byte[total];
        int cursor = 0;
        for (final byte[] block : blocks)
        {
            System.arraycopy(block, 0, out, cursor, block.length);
            cursor += block.length;
        }
        return out;
    }

    /** Encodes a name into a fixed-width, NUL-padded ASCII field. */
    static byte[] padded(final String text, final int width)
    {
        final byte[] out = new byte[width];
        final byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        int length = raw.length;
        if (length > width)
        {
            length = width;
        }
        System.arraycopy(raw, 0, out, 0, length);
        return out;
    }

    private int headerLumpCount()
    {
        if (declaredLumpCount >= 0)
        {
            return declaredLumpCount;
        }
        return payloads.size();
    }

    private int headerDirectoryOffset(final int realOffset)
    {
        if (declaredDirectoryOffset >= 0)
        {
            return declaredDirectoryOffset;
        }
        return realOffset;
    }
}
