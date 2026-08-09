/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine;

import com.openfps.engine.common.FixedMath;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fixed-point math utilities.
 */
class FixedMathTest
{
    @Test
    void shouldConvertFloatToFixedPoint()
    {
        assertEquals(FixedMath.UNIT, FixedMath.fromFloat(1.0f));

        assertEquals(0, FixedMath.fromFloat(0.0f));

        assertEquals(FixedMath.HALF, FixedMath.fromFloat(0.5f));
    }

    @Test
    void shouldRoundTrip()
    {
        final float original = 2.5f;

        final int fixed = FixedMath.fromFloat(original);

        final float recovered = FixedMath.toFloat(fixed);

        assertEquals(original, recovered, 0.001f);
    }

    @Test
    void shouldMultiplyCorrectly()
    {
        // 1.0 * 2.0 = 2.0
        assertEquals(FixedMath.UNIT * 2, FixedMath.mul(FixedMath.UNIT, FixedMath.fromFloat(2.0f)));

        // 0.5 * 4.0 = 2.0
        assertEquals(FixedMath.UNIT * 2, FixedMath.mul(FixedMath.HALF, FixedMath.fromFloat(4.0f)));
    }

    @Test
    void shouldDivideCorrectly()
    {
        // 1.0 / 2.0 = 0.5
        final int half = FixedMath.div(FixedMath.UNIT, FixedMath.fromFloat(2.0f));

        assertEquals(FixedMath.HALF, half);
    }

    @Test
    void shouldClampValues()
    {
        final int val = FixedMath.fromFloat(5.0f);

        final int min = FixedMath.fromFloat(1.0f);

        final int max = FixedMath.fromFloat(3.0f);

        assertEquals(max, FixedMath.clamp(val, min, max));

        assertEquals(min, FixedMath.clamp(FixedMath.fromFloat(0.5f), min, max));
    }

    @Test
    void shouldReturnAbsoluteValue()
    {
        assertEquals(FixedMath.UNIT, FixedMath.abs(-FixedMath.UNIT));

        assertEquals(FixedMath.UNIT, FixedMath.abs(FixedMath.UNIT));

        assertEquals(0, FixedMath.abs(0));
    }
}
