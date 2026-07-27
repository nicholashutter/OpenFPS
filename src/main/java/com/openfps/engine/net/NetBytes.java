/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

/**
 * G_ Big-endian (network byte order) primitive codec for the wire format.
 *
 * Package-private on purpose: this is the one place the net package converts
 * between primitives and bytes, so the byte order cannot drift between
 * {@link TicCmd} and {@link RedundantSender}.
 *
 * Every method reads from or writes into a caller-supplied array. Nothing here
 * allocates, so it is safe on the per-tic path (STYLE.md 13.4).
 */
final class NetBytes
{
    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final long INT_MASK = 0xFFFFFFFFL;

    private NetBytes()
    {
        // utility class
    }

    /**
     * Writes a 32-bit signed value at the given offset, most significant byte first.
     *
     * @param dst destination array
     * @param offset index of the first byte written
     * @param value the value to encode
     */
    static void writeInt(final byte[] dst, final int offset, final int value)
    {
        dst[offset] = (byte) (value >>> (BITS_PER_BYTE * 3));
        dst[offset + 1] = (byte) (value >>> (BITS_PER_BYTE * 2));
        dst[offset + 2] = (byte) (value >>> BITS_PER_BYTE);
        dst[offset + 3] = (byte) value;
    }

    /**
     * Reads a 32-bit signed value written by {@link #writeInt}.
     *
     * @param src source array
     * @param offset index of the first byte read
     * @return the decoded value
     */
    static int readInt(final byte[] src, final int offset)
    {
        return ((src[offset] & BYTE_MASK) << (BITS_PER_BYTE * 3))
            | ((src[offset + 1] & BYTE_MASK) << (BITS_PER_BYTE * 2))
            | ((src[offset + 2] & BYTE_MASK) << BITS_PER_BYTE)
            | (src[offset + 3] & BYTE_MASK);
    }

    /**
     * Writes the low 16 bits of the given value, most significant byte first.
     *
     * @param dst destination array
     * @param offset index of the first byte written
     * @param value the value to encode; only the low 16 bits are used
     */
    static void writeShort(final byte[] dst, final int offset, final int value)
    {
        dst[offset] = (byte) (value >>> BITS_PER_BYTE);
        dst[offset + 1] = (byte) value;
    }

    /**
     * Reads a 16-bit value as an unsigned integer in the range 0..65535.
     *
     * @param src source array
     * @param offset index of the first byte read
     * @return the decoded value, zero-extended
     */
    static int readUnsignedShort(final byte[] src, final int offset)
    {
        return ((src[offset] & BYTE_MASK) << BITS_PER_BYTE) | (src[offset + 1] & BYTE_MASK);
    }

    /**
     * Reads a 16-bit value as a signed integer in the range -32768..32767.
     *
     * @param src source array
     * @param offset index of the first byte read
     * @return the decoded value, sign-extended
     */
    static int readSignedShort(final byte[] src, final int offset)
    {
        return (short) readUnsignedShort(src, offset);
    }

    /**
     * Writes a 64-bit value at the given offset, most significant byte first.
     *
     * @param dst destination array
     * @param offset index of the first byte written
     * @param value the value to encode
     */
    static void writeLong(final byte[] dst, final int offset, final long value)
    {
        writeInt(dst, offset, (int) (value >>> Integer.SIZE));
        writeInt(dst, offset + Integer.BYTES, (int) value);
    }

    /**
     * Reads a 64-bit value written by {@link #writeLong}.
     *
     * @param src source array
     * @param offset index of the first byte read
     * @return the decoded value
     */
    static long readLong(final byte[] src, final int offset)
    {
        final long high = readInt(src, offset) & INT_MASK;
        final long low = readInt(src, offset + Integer.BYTES) & INT_MASK;
        return (high << Integer.SIZE) | low;
    }
}
