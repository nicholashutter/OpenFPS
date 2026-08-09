/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

/**
 * G_ Immutable lockstep input command for one player for one tic.
 *
 * ====================================================================
 *  WIRE LAYOUT — 12 BYTES, BIG-ENDIAN
 * ====================================================================
 * <pre>
 *  offset  size  field
 *  0       4     ticNumber
 *  4       2     forward        (-32768..32767, percent of max speed)
 *  6       2     strafe         (-32768..32767, percent of max speed)
 *  8       2     angle          (quantized yaw: 65536 steps / 360 deg)
 *  10      1     pitch          (quantized: 256 steps / 180 deg)
 *  11      1     buttons        (bitmask: fire, use, jump, ...)
 * </pre>
 *
 * {@code playerId} is deliberately NOT part of the command. It lives once in
 * the packet header, because every command in a packet comes from the same
 * sender. See net/README.md "TicCmd structure".
 *
 * There is no checksum field. A sender-computed checksum detects corruption,
 * which UDP already covers, and not tampering. Desync detection is a separate
 * periodic packet (net/README.md section 10).
 *
 * ====================================================================
 *  ALLOCATION
 * ====================================================================
 *  {@link #encode(byte[], int)} and every {@code decodeXxx} accessor write to
 *  or read from a caller-supplied array and allocate nothing, so they are safe
 *  on the per-tic path (STYLE.md 13.4). {@link #decode(byte[], int)} is the
 *  convenience form: it materialises one immutable value and is intended for
 *  tests and cold paths. Hot code should use {@link TicCmdBuffer}, which stores
 *  the fields unpacked in preallocated {@code int[]} rings.
 */
public final class TicCmd
{
    /** Encoded size of one command in bytes. */
    public static final int BYTES = 12;

    /** Lowest legal value of the forward and strafe axes. */
    public static final int MIN_AXIS = Short.MIN_VALUE;

    /** Highest legal value of the forward and strafe axes. */
    public static final int MAX_AXIS = Short.MAX_VALUE;

    /** Highest legal quantized yaw; the field is unsigned, 65536 steps over 360 degrees. */
    public static final int MAX_ANGLE = 0xFFFF;

    /** Lowest legal quantized pitch; the field is signed, 256 steps over 180 degrees. */
    public static final int MIN_PITCH = Byte.MIN_VALUE;

    /** Highest legal quantized pitch. */
    public static final int MAX_PITCH = Byte.MAX_VALUE;

    /** Button bitmask with every button pressed. */
    public static final int ALL_BUTTONS = 0xFF;

    private static final int OFFSET_TIC_NUMBER = 0;
    private static final int OFFSET_FORWARD = 4;
    private static final int OFFSET_STRAFE = 6;
    private static final int OFFSET_ANGLE = 8;
    private static final int OFFSET_PITCH = 10;
    private static final int OFFSET_BUTTONS = 11;

    private static final int PRIME = 31;

    private final int ticNumber;
    private final short forward;
    private final short strafe;
    private final short angle;
    private final byte pitch;
    private final byte buttons;

    /**
     * Creates an immutable command. Every field is range-checked, because a
     * value that does not survive the 12-byte encoding would silently desync
     * the peers that decode it.
     *
     * @param ticNumber the tic this input belongs to; must not be negative
     * @param forward forward axis, {@link #MIN_AXIS}..{@link #MAX_AXIS}
     * @param strafe strafe axis, {@link #MIN_AXIS}..{@link #MAX_AXIS}
     * @param angle quantized yaw, 0..{@link #MAX_ANGLE}
     * @param pitch quantized pitch, {@link #MIN_PITCH}..{@link #MAX_PITCH}
     * @param buttons button bitmask, 0..{@link #ALL_BUTTONS}
     */
    public TicCmd(final int ticNumber,
                  final int forward,
                  final int strafe,
                  final int angle,
                  final int pitch,
                  final int buttons)
    {
        requireRange(ticNumber, 0, Integer.MAX_VALUE, "ticNumber");
        requireRange(forward, MIN_AXIS, MAX_AXIS, "forward");
        requireRange(strafe, MIN_AXIS, MAX_AXIS, "strafe");
        requireRange(angle, 0, MAX_ANGLE, "angle");
        requireRange(pitch, MIN_PITCH, MAX_PITCH, "pitch");
        requireRange(buttons, 0, ALL_BUTTONS, "buttons");

        this.ticNumber = ticNumber;
        this.forward = (short) forward;
        this.strafe = (short) strafe;
        this.angle = (short) angle;
        this.pitch = (byte) pitch;
        this.buttons = (byte) buttons;
    }

    /** Returns the tic this input belongs to. */
    public int ticNumber()
    {
        return ticNumber;
    }

    /** Returns the forward axis, sign-extended to {@link #MIN_AXIS}..{@link #MAX_AXIS}. */
    public int forward()
    {
        return forward;
    }

    /** Returns the strafe axis, sign-extended to {@link #MIN_AXIS}..{@link #MAX_AXIS}. */
    public int strafe()
    {
        return strafe;
    }

    /** Returns the quantized yaw, zero-extended to 0..{@link #MAX_ANGLE}. */
    public int angle()
    {
        return angle & MAX_ANGLE;
    }

    /** Returns the quantized pitch, sign-extended to {@link #MIN_PITCH}..{@link #MAX_PITCH}. */
    public int pitch()
    {
        return pitch;
    }

    /** Returns the button bitmask, zero-extended to 0..{@link #ALL_BUTTONS}. */
    public int buttons()
    {
        return buttons & ALL_BUTTONS;
    }

    /**
     * Encodes this command into a caller-supplied buffer. Allocates nothing.
     *
     * @param dst destination buffer, at least {@code offset + BYTES} long
     * @param offset index of the first byte written
     * @return {@link #BYTES}, so callers can advance a cursor
     */
    public int encode(final byte[] dst, final int offset)
    {
        return encodeFields(dst, offset, ticNumber, forward, strafe, angle, pitch, buttons);
    }

    /**
     * Encodes loose fields into a caller-supplied buffer without building a
     * {@code TicCmd} first. This is the zero-allocation path used by
     * {@link TicCmdBuffer} and {@link RedundantSender}.
     *
     * @param dst destination buffer, at least {@code offset + BYTES} long
     * @param offset index of the first byte written
     * @param ticNumber the tic this input belongs to
     * @param forward forward axis; only the low 16 bits are written
     * @param strafe strafe axis; only the low 16 bits are written
     * @param angle quantized yaw; only the low 16 bits are written
     * @param pitch quantized pitch; only the low 8 bits are written
     * @param buttons button bitmask; only the low 8 bits are written
     * @return {@link #BYTES}
     */
    public static int encodeFields(final byte[] dst,
                                   final int offset,
                                   final int ticNumber,
                                   final int forward,
                                   final int strafe,
                                   final int angle,
                                   final int pitch,
                                   final int buttons)
    {
        NetBytes.writeInt(dst, offset + OFFSET_TIC_NUMBER, ticNumber);
        NetBytes.writeShort(dst, offset + OFFSET_FORWARD, forward);
        NetBytes.writeShort(dst, offset + OFFSET_STRAFE, strafe);
        NetBytes.writeShort(dst, offset + OFFSET_ANGLE, angle);
        dst[offset + OFFSET_PITCH] = (byte) pitch;
        dst[offset + OFFSET_BUTTONS] = (byte) buttons;
        return BYTES;
    }

    /**
     * Decodes a command from a caller-supplied buffer. This is the only method
     * here that allocates — one immutable value. Hot paths should use the
     * {@code decodeXxx} field accessors instead.
     *
     * @param src source buffer
     * @param offset index of the first byte read
     * @return the decoded command
     */
    public static TicCmd decode(final byte[] src, final int offset)
    {
        return new TicCmd(decodeTicNumber(src, offset),
            decodeForward(src, offset),
            decodeStrafe(src, offset),
            decodeAngle(src, offset),
            decodePitch(src, offset),
            decodeButtons(src, offset));
    }

    /**
     * Reads the tic number of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the encoded tic number
     */
    public static int decodeTicNumber(final byte[] src, final int offset)
    {
        return NetBytes.readInt(src, offset + OFFSET_TIC_NUMBER);
    }

    /**
     * Reads the forward axis of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the forward axis, sign-extended
     */
    public static int decodeForward(final byte[] src, final int offset)
    {
        return NetBytes.readSignedShort(src, offset + OFFSET_FORWARD);
    }

    /**
     * Reads the strafe axis of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the strafe axis, sign-extended
     */
    public static int decodeStrafe(final byte[] src, final int offset)
    {
        return NetBytes.readSignedShort(src, offset + OFFSET_STRAFE);
    }

    /**
     * Reads the quantized yaw of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the yaw, zero-extended to 0..{@link #MAX_ANGLE}
     */
    public static int decodeAngle(final byte[] src, final int offset)
    {
        return NetBytes.readUnsignedShort(src, offset + OFFSET_ANGLE);
    }

    /**
     * Reads the quantized pitch of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the pitch, sign-extended
     */
    public static int decodePitch(final byte[] src, final int offset)
    {
        return src[offset + OFFSET_PITCH];
    }

    /**
     * Reads the button bitmask of an encoded command without allocating.
     *
     * @param src source buffer
     * @param offset index of the first byte of the command
     * @return the bitmask, zero-extended to 0..{@link #ALL_BUTTONS}
     */
    public static int decodeButtons(final byte[] src, final int offset)
    {
        return src[offset + OFFSET_BUTTONS] & ALL_BUTTONS;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof TicCmd that))
        {
            return false;
        }
        return ticNumber == that.ticNumber
            && forward == that.forward
            && strafe == that.strafe
            && angle == that.angle
            && pitch == that.pitch
            && buttons == that.buttons;
    }

    @Override
    public int hashCode()
    {
        int result = ticNumber;
        result = PRIME * result + forward;
        result = PRIME * result + strafe;
        result = PRIME * result + angle;
        result = PRIME * result + pitch;
        result = PRIME * result + buttons;
        return result;
    }

    @Override
    public String toString()
    {
        return "TicCmd{tic=" + ticNumber
            + ", forward=" + forward
            + ", strafe=" + strafe
            + ", angle=" + angle()
            + ", pitch=" + pitch
            + ", buttons=" + buttons()
            + "}";
    }

    // Throws with the offending field named, so a desync source is obvious.
    private static void requireRange(final int value,
                                     final int min,
                                     final int max,
                                     final String field)
    {
        if (value < min || value > max)
        {
            throw new IllegalArgumentException(
                field + " out of range: " + value + " not in [" + min + ", " + max + "]");
        }
    }
}
