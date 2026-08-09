/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the 12-byte lockstep input command and its wire encoding.
 */
@DisplayName("TicCmd — the 12-byte lockstep input command")
class TicCmdTest
{
    private static final int SCRATCH_BYTES = 64;
    private static final int SENTINEL = 0x5A;

    @Test
    @DisplayName("occupies exactly twelve bytes on the wire, because cmd size is the binding "
        + "bandwidth constraint")
    void encodedSizeIsTwelveBytes()
    {
        assertThat(TicCmd.BYTES).isEqualTo(12);

        final byte[] buffer = new byte[SCRATCH_BYTES];

        fill(buffer);

        final TicCmd cmd = new TicCmd(1, 2, 3, 4, 5, 6);

        assertThat(cmd.encode(buffer, 0)).isEqualTo(TicCmd.BYTES);

        assertThat(buffer[TicCmd.BYTES]).isEqualTo((byte) SENTINEL);
    }

    @Test
    @DisplayName("survives an encode and decode round trip unchanged")
    void roundTripsThroughTheWireFormat()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        final TicCmd cmd = new TicCmd(1234, -500, 900, 40000, -60, 0x0F);

        cmd.encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0)).isEqualTo(cmd);
    }

    @Test
    @DisplayName("round trips the minimum and maximum signed 16-bit axis values")
    void roundTripsAxisBoundaries()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        final TicCmd cmd = new TicCmd(0, TicCmd.MIN_AXIS, TicCmd.MAX_AXIS, 0, 0, 0);

        cmd.encode(buffer, 0);

        final TicCmd decoded = TicCmd.decode(buffer, 0);

        assertThat(decoded.forward()).isEqualTo(TicCmd.MIN_AXIS);

        assertThat(decoded.strafe()).isEqualTo(TicCmd.MAX_AXIS);
    }

    @Test
    @DisplayName("treats the yaw field as unsigned so the whole 65536-step circle survives")
    void roundTripsUnsignedAngleBoundaries()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        new TicCmd(0, 0, 0, 0, 0, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).angle()).isZero();

        new TicCmd(0, 0, 0, TicCmd.MAX_ANGLE, 0, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).angle()).isEqualTo(TicCmd.MAX_ANGLE);

        final int halfCircle = (TicCmd.MAX_ANGLE + 1) / 2;

        new TicCmd(0, 0, 0, halfCircle, 0, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).angle()).isEqualTo(halfCircle);
    }

    @Test
    @DisplayName("round trips the minimum and maximum signed pitch values")
    void roundTripsPitchBoundaries()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        new TicCmd(0, 0, 0, 0, TicCmd.MIN_PITCH, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).pitch()).isEqualTo(TicCmd.MIN_PITCH);

        new TicCmd(0, 0, 0, 0, TicCmd.MAX_PITCH, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).pitch()).isEqualTo(TicCmd.MAX_PITCH);
    }

    @Test
    @DisplayName("round trips a button mask with every button held down")
    void roundTripsAllButtonsSet()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        new TicCmd(0, 0, 0, 0, 0, TicCmd.ALL_BUTTONS).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).buttons()).isEqualTo(TicCmd.ALL_BUTTONS);
    }

    @Test
    @DisplayName("round trips the largest tic number the field can hold")
    void roundTripsMaximumTicNumber()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        new TicCmd(Integer.MAX_VALUE, 0, 0, 0, 0, 0).encode(buffer, 0);

        assertThat(TicCmd.decode(buffer, 0).ticNumber()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("writes at the caller's offset and disturbs nothing on either side of it")
    void writesOnlyItsOwnTwelveBytes()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        fill(buffer);

        final int offset = 7;

        final TicCmd cmd = new TicCmd(99, -1, 1, TicCmd.MAX_ANGLE, -1, 1);

        cmd.encode(buffer, offset);

        for (int i = 0; i < offset; i++)
        {
            assertThat(buffer[i]).isEqualTo((byte) SENTINEL);
        }

        for (int i = offset + TicCmd.BYTES; i < buffer.length; i++)
        {
            assertThat(buffer[i]).isEqualTo((byte) SENTINEL);
        }

        assertThat(TicCmd.decode(buffer, offset)).isEqualTo(cmd);
    }

    @Test
    @DisplayName("lays the tic number out big-endian, as network byte order requires")
    void writesTheTicNumberBigEndian()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        new TicCmd(0x01020304, 0, 0, 0, 0, 0).encode(buffer, 0);

        assertThat(buffer[0]).isEqualTo((byte) 0x01);

        assertThat(buffer[1]).isEqualTo((byte) 0x02);

        assertThat(buffer[2]).isEqualTo((byte) 0x03);

        assertThat(buffer[3]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("reads each field with the allocation-free decoders exactly as the object form does")
    void fieldDecodersAgreeWithTheObjectForm()
    {
        final byte[] buffer = new byte[SCRATCH_BYTES];

        final TicCmd cmd = new TicCmd(77, TicCmd.MIN_AXIS, TicCmd.MAX_AXIS,
            TicCmd.MAX_ANGLE, TicCmd.MIN_PITCH, TicCmd.ALL_BUTTONS);

        cmd.encode(buffer, 3);

        assertThat(TicCmd.decodeTicNumber(buffer, 3)).isEqualTo(cmd.ticNumber());

        assertThat(TicCmd.decodeForward(buffer, 3)).isEqualTo(cmd.forward());

        assertThat(TicCmd.decodeStrafe(buffer, 3)).isEqualTo(cmd.strafe());

        assertThat(TicCmd.decodeAngle(buffer, 3)).isEqualTo(cmd.angle());

        assertThat(TicCmd.decodePitch(buffer, 3)).isEqualTo(cmd.pitch());

        assertThat(TicCmd.decodeButtons(buffer, 3)).isEqualTo(cmd.buttons());
    }

    @Test
    @DisplayName("produces identical bytes whether encoded from an object or from loose fields")
    void looseFieldEncodingMatchesObjectEncoding()
    {
        final byte[] fromObject = new byte[TicCmd.BYTES];

        final byte[] fromFields = new byte[TicCmd.BYTES];

        new TicCmd(42, -300, 300, 65000, -100, 200).encode(fromObject, 0);

        TicCmd.encodeFields(fromFields, 0, 42, -300, 300, 65000, -100, 200);

        assertThat(fromFields).isEqualTo(fromObject);
    }

    @Test
    @DisplayName("rejects a field that would not survive the encoding, rather than desyncing peers")
    void rejectsOutOfRangeFields()
    {
        assertThatThrownBy(() -> new TicCmd(-1, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ticNumber");

        assertThatThrownBy(() -> new TicCmd(0, TicCmd.MIN_AXIS - 1, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forward");

        assertThatThrownBy(() -> new TicCmd(0, 0, TicCmd.MAX_AXIS + 1, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strafe");

        assertThatThrownBy(() -> new TicCmd(0, 0, 0, TicCmd.MAX_ANGLE + 1, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("angle");

        assertThatThrownBy(() -> new TicCmd(0, 0, 0, 0, TicCmd.MAX_PITCH + 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pitch");

        assertThatThrownBy(() -> new TicCmd(0, 0, 0, 0, 0, TicCmd.ALL_BUTTONS + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("buttons");
    }

    @Test
    @DisplayName("compares equal by value and hashes consistently")
    void comparesByValue()
    {
        final TicCmd left = new TicCmd(5, 1, 2, 3, 4, 5);

        final TicCmd right = new TicCmd(5, 1, 2, 3, 4, 5);

        final TicCmd other = new TicCmd(6, 1, 2, 3, 4, 5);

        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

        assertThat(left).isNotEqualTo(other);

        assertThat(left).isNotEqualTo("not a cmd");

        assertThat(left).isEqualTo(left);
    }

    @Test
    @DisplayName("prints its fields for a readable failure message")
    void describesItself()
    {
        assertThat(new TicCmd(9, 1, 2, 3, 4, 5).toString())
            .contains("tic=9")
            .contains("buttons=5");
    }

    private static void fill(final byte[] buffer)
    {
        for (int i = 0; i < buffer.length; i++)
        {
            buffer[i] = (byte) SENTINEL;
        }
    }
}
