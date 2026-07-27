/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * The float comparison tolerance used by the render math tests, and the
 * argument for its magnitude.
 *
 * <p><b>Why not exact equality?</b> Most of these assertions could in fact use
 * it — the four arithmetic operators and {@code Math.sqrt} are exactly rounded
 * and, since JEP 306, always-strict on every conforming JVM, so a hand-computed
 * expected value that happens to take the same rounding path matches bit for
 * bit. But
 * "happens to take the same rounding path" is the load-bearing phrase: an
 * expected value written as {@code 0.5f * a + 0.5f * b} and an implementation
 * computing {@code a + t * (b - a)} are algebraically identical and can differ
 * in the last bit. A test that pins the last bit is testing the order of
 * operations, not the math.</p>
 *
 * <p><b>Why 1e-5f?</b> Derived, not tuned until green:</p>
 * <ul>
 *   <li>{@code float} carries a 24-bit significand, so one rounding near
 *       magnitude 1 is at most {@code 2^-24} ~ 6e-8, and {@code Math.ulp(1.0f)}
 *       is 1.19e-7.</li>
 *   <li>The longest dependent chain in this code is one row of the
 *       world-to-clip transform: three multiplies and three adds, six
 *       roundings, so ~6 ulp ~ 7e-7 relative in the worst case. The clipper's
 *       lerp is three roundings on top of the {@code t} division, so ~5 ulp.</li>
 *   <li>1e-5 is roughly 84 ulp at magnitude 1 — over an order of magnitude of
 *       headroom above the worst realistic accumulation.</li>
 *   <li>It is still nowhere near loose enough to hide a real defect. Every
 *       error this suite is designed to catch — a transposed matrix, a dropped
 *       aspect divide, a restored z row, an attribute interpolated at the wrong
 *       {@code t}, a flipped handedness — moves a result by O(0.1) or more,
 *       four orders of magnitude above this tolerance.</li>
 * </ul>
 *
 * <p>Assertions on quantities of magnitude much greater than 1 use a
 * proportionally scaled tolerance rather than this absolute one, because an
 * absolute epsilon is a relative epsilon in disguise and stops meaning anything
 * once the values grow.</p>
 */
final class RenderTestEpsilon
{
    /** Absolute tolerance for float comparisons on quantities of order 1. */
    static final float EPSILON = 1.0e-5f;

    private RenderTestEpsilon()
    {
        // constants holder — no instances
    }
}
