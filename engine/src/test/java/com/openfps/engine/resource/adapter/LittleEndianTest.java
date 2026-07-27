/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the little-endian readers.
 *
 * The point of these tests is that a big-endian reading would produce a
 * different, wrong answer for every fixture here — the byte patterns are
 * chosen so an accidental byte-swap cannot pass.
 */
class LittleEndianTest
{
    @Test
    @DisplayName("a 32-bit read takes the first byte as least significant")
    void shouldReadInt32LittleEndian()
    {
        final byte[] src = WadBuilder.bytes(0x04, 0x03, 0x02, 0x01);

        assertThat(LittleEndian.int32(src, 0)).isEqualTo(0x01020304);
    }

    @Test
    @DisplayName("a 32-bit read of a big-endian byte order gives the swapped value")
    void shouldNotReadInt32BigEndian()
    {
        final byte[] src = WadBuilder.bytes(0x01, 0x02, 0x03, 0x04);

        assertThat(LittleEndian.int32(src, 0)).isEqualTo(0x04030201);
        assertThat(LittleEndian.int32(src, 0)).isNotEqualTo(0x01020304);
    }

    @Test
    @DisplayName("a 32-bit read honours the offset it is given")
    void shouldReadInt32AtOffset()
    {
        final byte[] src = WadBuilder.bytes(0xFF, 0xFF, 0x04, 0x03, 0x02, 0x01);

        assertThat(LittleEndian.int32(src, 2)).isEqualTo(0x01020304);
    }

    @Test
    @DisplayName("a 32-bit read of all ones is negative one")
    void shouldReadNegativeInt32()
    {
        final byte[] src = WadBuilder.bytes(0xFF, 0xFF, 0xFF, 0xFF);

        assertThat(LittleEndian.int32(src, 0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("an unsigned 16-bit read never returns a negative number")
    void shouldReadUint16AsUnsigned()
    {
        final byte[] src = WadBuilder.bytes(0xFF, 0xFF, 0x00, 0x80);

        assertThat(LittleEndian.uint16(src, 0)).isEqualTo(0xFFFF);
        assertThat(LittleEndian.uint16(src, 2)).isEqualTo(0x8000);
    }

    @Test
    @DisplayName("a signed 16-bit read sign-extends values above 0x7FFF")
    void shouldReadInt16AsSigned()
    {
        final byte[] src = WadBuilder.words(0, 1, -1, 32767, -32768, -1024);

        assertThat(LittleEndian.int16(src, 0)).isZero();
        assertThat(LittleEndian.int16(src, 2)).isEqualTo(1);
        assertThat(LittleEndian.int16(src, 4)).isEqualTo(-1);
        assertThat(LittleEndian.int16(src, 6)).isEqualTo(32767);
        assertThat(LittleEndian.int16(src, 8)).isEqualTo(-32768);
        assertThat(LittleEndian.int16(src, 10)).isEqualTo(-1024);
    }

    @Test
    @DisplayName("an ASCII field stops at the first NUL and is upper-cased")
    void shouldReadNulPaddedName()
    {
        final byte[] src = WadBuilder.padded("e1m1", WadBuilder.NAME_LENGTH);

        assertThat(LittleEndian.ascii(src, 0, WadBuilder.NAME_LENGTH)).isEqualTo("E1M1");
    }

    @Test
    @DisplayName("an ASCII field that fills its full width reads every character")
    void shouldReadFullWidthName()
    {
        final byte[] src = WadBuilder.padded("SW1BRCOM", WadBuilder.NAME_LENGTH);

        assertThat(LittleEndian.ascii(src, 0, WadBuilder.NAME_LENGTH)).isEqualTo("SW1BRCOM");
    }

    @Test
    @DisplayName("an all-NUL ASCII field reads as the empty string")
    void shouldReadEmptyName()
    {
        final byte[] src = WadBuilder.padded("", WadBuilder.NAME_LENGTH);

        assertThat(LittleEndian.ascii(src, 0, WadBuilder.NAME_LENGTH)).isEmpty();
    }

    @Test
    @DisplayName("an ASCII field does not read past its declared width")
    void shouldNotReadPastNameWidth()
    {
        final byte[] src = WadBuilder.concat(
            WadBuilder.padded("FLOOR4_8", WadBuilder.NAME_LENGTH),
            WadBuilder.padded("CEIL3_5", WadBuilder.NAME_LENGTH));

        assertThat(LittleEndian.ascii(src, 0, WadBuilder.NAME_LENGTH)).isEqualTo("FLOOR4_8");
        assertThat(LittleEndian.ascii(src, WadBuilder.NAME_LENGTH, WadBuilder.NAME_LENGTH))
            .isEqualTo("CEIL3_5");
    }
}
