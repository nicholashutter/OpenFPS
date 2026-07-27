/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.common;

/**
 * Fixed-point math utilities using 16.16 fixed-point representation.
 * 1.0 unit = 0x10000 (65536). All values are stored as primitive int to
 * avoid boxing overhead.
 *
 * ====================================================================
 *  WHY FIXED-POINT INSTEAD OF float/double?
 * ====================================================================
 *  The original DOOM (1993) used fixed-point because 386/486 CPUs had no
 *  FPU and integer math was 5-10x faster. We use it for different reasons:
 *
 *  1. JVM boxing cost  — every Float / Double allocates. int does not.
 *  2. JIT inlining     — int math inlines better, fewer spills.
 *  3. Determinism      — int * int is the same on x86 and ARM. float * float
 *                        can differ in the last bit, which breaks P2P
 *                        lockstep. See net/README.md for why this matters.
 *  4. Cache locality   — int[] is half the size of float[].
 *  5. SIMD-friendly    — Vector API (JEP 438) packs ints 4x denser.
 *
 *  Tradeoff: limited range (int = ±2.1 billion = ±32 768 map units in 16.16)
 *  and 1/65536 precision (~0.000015 unit). Plenty for 2D floorplans.
 *
 *  References:
 *  - DOOM source z_zone.c, m_fixed.c, tables.c:
 *    https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/m_fixed.c
 *  - "Using Fixed-Point Math" (GameDev.net):
 *    https://www.gamedev.net/tutorials/_/technical/math-and-physics/using-fixed-point-math-r2528/
 *  - Tricks of the Windows Game Programming Gurus, Ch. 4 (LaMothe, 2002)
 *
 * ====================================================================
 *  16.16 FORMAT ENCODING
 * ====================================================================
 *       1.0  = 0x00010000 = 65536
 *       0.5  = 0x00008000 = 32768
 *       0.0  = 0x00000000 = 0
 *      -1.0  = 0xFFFF0000 = -65536
 *
 *  Conversion:
 *      int fixed  = (int)(floatValue * 65536.0f);
 *      float back = fixed / 65536.0f;
 *
 *  Reference:
 *  - https://en.wikipedia.org/wiki/Fixed-point_arithmetic
 *  - DOOM source m_fixed.c lines 40-55 (FRACUNIT = 0x10000)
 *
 * ====================================================================
 *  16.16 × 16.16 → 16.16 MULTIPLICATION
 * ====================================================================
 *  Conceptually: a and b are 16.16. The product is 32.32. We need to
 *  shift right by 16 to get back to 16.16. The product is 64-bit so
 *  we don't overflow the int.
 *
 *      int mul(int a, int b) {
 *          return (int)(((long)a * b) >> 16);
 *      }
 *
 *  The cast to (long) is mandatory — without it, the int * int product
 *  overflows before we can shift.
 *
 *  Why not just use floats? See the "Why Fixed-Point" section above.
 *
 *  Reference:
 *  - https://en.wikipedia.org/wiki/Q_(number_format)#Arithmetic_operations
 *
 * ====================================================================
 *  16.16 ÷ 16.16 → 16.16 DIVISION
 * ====================================================================
 *  To divide we shift the numerator left by 16 to pre-align, then do
 *  an integer divide. The intermediate ((long)a << 16) is 48-bit, fits
 *  in a long.
 *
 *      int div(int a, int b) {
 *          return (int)(((long)a << 16) / b);
 *      }
 *
 *  This is the inverse of the multiplication. Sanity check:
 *      mul(a, div(UNIT, b)) == a / b   (modulo rounding)
 *
 *  Reference:
 *  - DOOM source m_fixed.c FixedDiv()
 *
 * ====================================================================
 *  TRIG — NOT YET IMPLEMENTED
 * ====================================================================
 *  Angle representation and the sin/cos lookup tables are Phase 4/5
 *  work (first real consumer is player movement in gameplay, then the
 *  renderer). An earlier draft shipped a 1024-entry table indexed by
 *  degrees, which silently disagreed with its own documented
 *  degrees × 65536 angle convention — it was removed rather than left
 *  as a trap. Design it against a real caller and a real test.
 *
 *  References for when that lands:
 *  - DOOM source tables.c (finesine[], angle_t)
 *  - Michael Abrash, Graphics Programming Black Book, Ch. 50 "Fast Trig"
 */
public final class FixedMath
{
    private FixedMath()
    {
        // utility class — no instances
    }

    // ---- Fixed-point constants ----

    /** Fixed-point representation of 1.0 (one world unit). 1.0 = 0x10000. */
    public static final int UNIT = 0x10000;

    /** Fixed-point representation of 0.5 (half a world unit). */
    public static final int HALF = 0x8000;

    // ===============================================================
    //  CONVERSION
    // ===============================================================

    /**
     * Converts a primitive float to 16.16 fixed-point int.
     *
     * Math: result = value × 65536
     * Source: DOOM source m_fixed.c FixedMul(FixedDiv(value, 1), FRACUNIT)
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
     * Math: result = value / 65536
     *
     * @param value the fixed-point value
     * @return the value as float
     */
    public static float toFloat(final int value)
    {
        return value / (float) UNIT;
    }

    // ===============================================================
    //  ARITHMETIC
    // ===============================================================

    /**
     * Multiplies two 16.16 fixed-point values.
     * Math: (a * b) >> 16, computed as long to avoid overflow.
     *
     * Worked example:
     *   0.5 * 0.5 = 0.25
     *   mul(0x8000, 0x8000) = ((long)0x8000 * 0x8000) >> 16
     *                     = (0x40000000) >> 16
     *                     = 0x4000
     *                     = 0.25 in 16.16  ✓
     *
     * @param a first operand (16.16)
     * @param b second operand (16.16)
     * @return a × b in 16.16
     */
    public static int mul(final int a, final int b)
    {
        return (int) (((long) a * b) >> 16);
    }

    /**
     * Divides two 16.16 fixed-point values.
     * Math: (a << 16) / b, computed as long to avoid overflow.
     *
     * Worked example:
     *   1.0 / 4.0 = 0.25
     *   div(0x10000, 0x40000) = ((long)0x10000 << 16) / 0x40000
     *                       = 0x100000000 / 0x40000
     *                       = 0x400
     *                       = 0.25 in 16.16  ✓
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
     * Math: |value|. Be careful — INT_MIN has no positive counterpart in
     * 2's complement. If the engine ever produces INT_MIN, this will
     * overflow. We don't expect to see INT_MIN in practice.
     *
     * @param value the fixed-point value
     * @return abs(value)
     */
    public static int abs(final int value)
    {
        if (value < 0)
        {
            return -value;
        }
        return value;
    }

    /**
     * Clamps a fixed-point value to a range.
     * Math: max(min, min(value, max))
     *
     * Branchless alternative: use Math.max / Math.min, which JVM
     * intrinsics optimize. We use explicit branches for clarity since
     * the JIT will optimize both equally well.
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
}
