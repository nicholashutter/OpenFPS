/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.common;

/**
 * Fixed-point math utilities using 16.16 fixed-point representation.
 * 1.0 unit = 0x10000 (65536).
 * All values are stored as primitive int to avoid boxing overhead.
 */
public final class FixedMath
{
    private FixedMath()
    {
        // utility class
    }

    /** Fixed-point representation of 1.0 (one world unit). */
    public static final int UNIT = 0x10000;

    /** Fixed-point representation of 0.5 (half a world unit). */
    public static final int HALF = 0x8000;

    /** π × 2 in 16.16 fixed point (full circle = 360°). */
    public static final int PI_TIMES_2 = 196608;  // ≈ 3.0 * UNIT

    /** π in 16.16 fixed point. */
    public static final int PI = 102944;           // ≈ 1.571 * UNIT

    /**
     * Converts a primitive float to 16.16 fixed-point int.
     *
     * @param value the floating-point value
     * @return the value as 16.16 fixed-point
     */
    public static int fromFloat(final float value)
    {
        return (int) (value * UNIT);
    }

    /**
     * Converts a 16.16 fixed-point int back to float.
     *
     * @param value the fixed-point value
     * @return the value as float
     */
    public static float toFloat(final int value)
    {
        return value / (float) UNIT;
    }

    /**
     * Multiplies two 16.16 fixed-point values.
     * Result is also 16.16.
     *
     * @param a first operand
     * @param b second operand
     * @return a × b in 16.16
     */
    public static int mul(final int a, final int b)
    {
        return (int) (((long) a * b) >> 16);
    }

    /**
     * Divides two 16.16 fixed-point values.
     *
     * @param a numerator (16.16)
     * @param b denominator (16.16)
     * @return a / b in 16.16
     */
    public static int div(final int a, final int b)
    {
        return (int) (((long) a << 16) / b);
    }

    /**
     * Returns the absolute value of a fixed-point number.
     *
     * @param value the fixed-point value
     * @return abs(value)
     */
    public static int abs(final int value)
    {
        return value < 0 ? -value : value;
    }

    /**
     * Clamps a fixed-point value to a range.
     *
     * @param value the value to clamp
     * @param min the minimum (fixed-point)
     * @param max the maximum (fixed-point)
     * @return the clamped value
     */
    public static int clamp(final int value, final int min, final int max)
    {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Converts an angle in degrees × 65536 to a unit vector (x, y).
     * Returns x and y packed in a long: upper 32 bits = x, lower 32 bits = y.
     *
     * @param angle angle in degrees × 65536
     * @return long containing (x << 32) | (y & 0xFFFFFFFFL)
     */
    public static long angleToVector(final int angle)
    {
        // Use the low 16 bits of angle for fine resolution
        final int a = angle >> 16;
        final int cos = cosTable(a & 0x3FF);
        final int sin = sinTable(a & 0x3FF);
        return (((long) cos) << 32) | (((long) sin) & 0xFFFFFFFFL);
    }

    // ---- Trigonometry tables (degree-based, 0–359) ----

    private static int cosTable(final int index)
    {
        return COS_TABLE[index & 0x3FF];
    }

    private static int sinTable(final int index)
    {
        return SIN_TABLE[index & 0x3FF];
    }

    private static final int[] COS_TABLE = new int[1024];
    private static final int[] SIN_TABLE = new int[1024];

    static
    {
        for (int i = 0; i < 1024; i++)
        {
            final double rad = Math.toRadians(i * 360.0 / 1024.0);
            COS_TABLE[i] = (int) (Math.cos(rad) * UNIT);
            SIN_TABLE[i] = (int) (Math.sin(rad) * UNIT);
        }
    }
}
