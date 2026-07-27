/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Little-endian primitive readers for WAD data.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * EVERY field in a WAD file is little-endian: the format was defined on
 * a 486 and never byte-swapped. The engine's other byte-packing site,
 * {@code memory/adapter/ZoneMemoryPort}, packs its allocation headers
 * BIG-endian because nothing outside the zone heap ever reads them.
 * Do not copy that code here — the two are deliberately different, and
 * mixing them silently produces garbage lump offsets rather than an
 * error. These helpers exist so there is exactly one little-endian
 * implementation in the resource subsystem.
 *
 * All readers take a raw offset into the caller's buffer and perform no
 * bounds checking beyond what the JVM already does on array access: they
 * sit on the map-load path and the callers validate ranges up front.
 */
public final class LittleEndian
{
    /** Number of bits in one byte. */
    private static final int BITS_PER_BYTE = 8;

    /** Mask isolating the low 8 bits of a sign-extended byte. */
    private static final int BYTE_MASK = 0xFF;

    /** Sign bit of a 16-bit word. */
    private static final int WORD_SIGN_BIT = 0x8000;

    /** One past the largest unsigned 16-bit value, used to sign-extend. */
    private static final int WORD_RANGE = 0x10000;

    private LittleEndian()
    {
        // utility class
    }

    /**
     * Reads a signed 32-bit little-endian integer.
     *
     * @param src buffer to read from
     * @param offset byte offset of the first (least significant) byte
     * @return the decoded value
     */
    public static int int32(final byte[] src, final int offset)
    {
        return (src[offset] & BYTE_MASK)
            | ((src[offset + 1] & BYTE_MASK) << BITS_PER_BYTE)
            | ((src[offset + 2] & BYTE_MASK) << (2 * BITS_PER_BYTE))
            | ((src[offset + 3] & BYTE_MASK) << (3 * BITS_PER_BYTE));
    }

    /**
     * Reads an unsigned 16-bit little-endian integer, widened to int.
     * The result is always in the range 0..65535.
     *
     * @param src buffer to read from
     * @param offset byte offset of the first (least significant) byte
     * @return the decoded value
     */
    public static int uint16(final byte[] src, final int offset)
    {
        return (src[offset] & BYTE_MASK)
            | ((src[offset + 1] & BYTE_MASK) << BITS_PER_BYTE);
    }

    /**
     * Reads a signed 16-bit little-endian integer, widened to int.
     * The result is always in the range -32768..32767.
     *
     * @param src buffer to read from
     * @param offset byte offset of the first (least significant) byte
     * @return the decoded value
     */
    public static int int16(final byte[] src, final int offset)
    {
        final int raw = uint16(src, offset);
        if ((raw & WORD_SIGN_BIT) != 0)
        {
            return raw - WORD_RANGE;
        }
        return raw;
    }

    /**
     * Reads a fixed-width, NUL-padded ASCII name — the encoding WAD uses
     * for lump names and texture names. Reading stops at the first NUL or
     * at {@code maxLength}, whichever comes first. Surrounding whitespace
     * is trimmed and the result is upper-cased, because DOOM lump names
     * are conventionally uppercase and lookups must not depend on the
     * casing an authoring tool happened to write.
     *
     * @param src buffer to read from
     * @param offset byte offset of the first character
     * @param maxLength field width in bytes
     * @return the decoded name, possibly empty, never null
     */
    public static String ascii(final byte[] src, final int offset, final int maxLength)
    {
        int length = 0;
        while (length < maxLength && src[offset + length] != 0)
        {
            length++;
        }
        final String raw = new String(src, offset, length, StandardCharsets.US_ASCII);
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
