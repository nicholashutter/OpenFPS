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
 *  TRIG TABLES (1024-entry sin/cos)
 * ====================================================================
 *  We pre-compute sin and cos for 1024 angles around the full circle
 *  (0..360° in steps of 0.3515625°). Index is (angle & 0x3FF).
 *
 *  The original DOOM used 8192-entry tables (finer, 0.044° resolution)
 *  because their CPU had no FPU. Modern hardware can compute trig on the
 *  fly faster than a cache miss, but tables still win for predictable
 *  branch-free code in inner loops.
 *
 *  Math source:
 *      cos(theta) = sin(theta + π/2)
 *      sin(theta) = (y / r) of a point on the unit circle at angle theta
 *
 *  Tables built at class-load time using Math.sin / Math.cos for
 *  initialization, then never touched again.
 *
 *  References:
 *  - https://en.wikipedia.org/wiki/Trigonometric_tables
 *  - Michael Abrash, Graphics Programming Black Book, Ch. 50 "Fast Trig"
 *  - "How DOOM's tables work" — Fabien Sanglard:
 *    http://www.fabiensanglard.net/doomIphone/doomIphone.php
 *
 * ====================================================================
 *  ANGLE CONVENTION
 * ====================================================================
 *  DOOM stores angles as unsigned 32-bit integers:
 *      0x00000000 = East (0°)
 *      0x40000000 = North (90°)
 *      0x80000000 = West (180°)
 *      0xC0000000 = South (270°)
 *
 *  We use a simpler convention: angle is stored as degrees × 65536
 *  (int). So 360° = 360 * 65536 = 23592960. This is what FixedMath.angleToVector
 *  expects.
 *
 *  For trig lookup we shift right by 16 to extract the integer-degree
 *  part, then mask with 0x3FF to wrap into the table.
 *
 *  Why degrees × 65536, not 4096 like DOOM? Because 16.16 fixed-point
 *  is the natural unit everywhere else in the engine, and mixing units
 *  would require constant conversion. Tradeoff: angle is now a 32-bit
 *  int where only the upper 16 bits are meaningful, but it makes
 *  serialization (P2P tic commands) easier.
 *
 *  References:
 *  - DOOM source tables.c (angle constants)
 *  - DOOM source r_main.c (R_PointToAngle)
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

    /** 2π in 16.16 fixed point (full circle). ≈ 6.2832 * 0x10000. */
    public static final int PI_TIMES_2 = 411774;

    /** π in 16.16 fixed point. ≈ 3.1416 * 0x10000. */
    public static final int PI = 205887;

    /** Conversion factor: 360° in degrees × 65536. Used for angle wraparound. */
    public static final int ANG_360 = 360 * UNIT;

    // ---- Bit layout for angles ----
    // For trig lookups we shift an angle by 16 (extract degree count) and
    // mask with 0x3FF (1024-entry table). See "ANGLE CONVENTION" above.

    /** Mask for table index — 1024 entries cover the full circle. */
    public static final int TRIG_MASK = 0x3FF;

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
        return value < 0 ? -value : value;
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

    // ===============================================================
    //  TRIG
    // ===============================================================

    /**
     * Computes a unit vector (cos, sin) for a given angle.
     * Returns the components packed in a long: upper 32 bits = cos,
     * lower 32 bits = sin. Packing avoids allocating a 2-element array.
     *
     * Math:
     *   index = (angle >> 16) & 0x3FF     (extract degree count, wrap)
     *   x = cos(index * 2π / 1024)
     *   y = sin(index * 2π / 1024)
     *
     * Source: DOOM source tables.c finesine[], angle_t lookup pattern
     *
     * @param angle angle in degrees × 65536 (see ANGLE CONVENTION)
     * @return long containing (cos << 32) | (sin & 0xFFFFFFFFL)
     */
    public static long angleToVector(final int angle)
    {
        final int tableIndex = (angle >> 16) & TRIG_MASK;
        final int cos = COS_TABLE[tableIndex];
        final int sin = SIN_TABLE[tableIndex];
        return (((long) cos) << 32) | (((long) sin) & 0xFFFFFFFFL);
    }

    /**
     * Looks up cos for a given angle.
     * Exposed for places that need only one component.
     *
     * @param angle angle in degrees × 65536
     * @return cos(angle) in 16.16
     */
    public static int cos(final int angle)
    {
        return COS_TABLE[(angle >> 16) & TRIG_MASK];
    }

    /**
     * Looks up sin for a given angle.
     * Exposed for places that need only one component.
     *
     * @param angle angle in degrees × 65536
     * @return sin(angle) in 16.16
     */
    public static int sin(final int angle)
    {
        return SIN_TABLE[(angle >> 16) & TRIG_MASK];
    }

    // ===============================================================
    //  TRIG TABLES (built once at class load)
    // ===============================================================
    //
    //  Math: for index i in 0..1023, angle = i * (2π / 1024) radians.
    //        cos = cos(angle), sin = sin(angle), each scaled to 16.16.
    //
    //  Precision: 360 / 1024 = 0.3515625° per entry. For most game
    //  math (projectile arc, wall normal) this is fine. For pixel-perfect
    //  aiming, we'd need 4096 or 8192 entries (the DOOM approach).
    //
    //  Built at static-init time using Math.sin / Math.cos. After init,
    //  the tables are read-only — never written.
    //
    //  References:
    //  - https://en.wikipedia.org/wiki/Sine
    //  - https://en.wikipedia.org/wiki/Trigonometric_functions
    //  - Michael Abrash, "Fast Trig Functions", GPBB Ch. 50

    private static final int[] COS_TABLE = new int[1024];
    private static final int[] SIN_TABLE = new int[1024];

    static
    {
        for (int i = 0; i < 1024; i++)
        {
            // angle in radians: i × (2π / 1024)
            final double rad = (i * 2.0 * Math.PI) / 1024.0;
            COS_TABLE[i] = (int) (Math.cos(rad) * UNIT);
            SIN_TABLE[i] = (int) (Math.sin(rad) * UNIT);
        }
    }
}
