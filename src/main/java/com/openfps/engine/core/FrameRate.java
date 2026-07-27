/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

/**
 * Closed set of frame rates supported by the engine.
 *
 * The engine runs at one of three rates: 30, 60, or 120 Hz. Anything
 * else is rejected at construction — there is no "auto-detect", no
 * arbitrary-rate mode, no "user can pass any number". This is by
 * design (see Plan C in commit history).
 *
 * ====================================================================
 *  WHY ONLY 30 / 60 / 120?
 * ====================================================================
 *
 *  - 30 Hz:  console target, low-end / power-save mode
 *  - 60 Hz:  standard PC display, console target, default for headless tests
 *  - 120 Hz: high-refresh gaming displays
 *
 *  Excluded:
 *  - 144 Hz: niche esports (~1% of displays), marginal benefit
 *  - 240 Hz: extreme niche, no consumer hardware target
 *  - Any arbitrary rate: drift-correction math is harder; testing matrix
 *    explodes
 *
 *  Adding a new rate later is one line of code (a new enum value) plus
 *  one test. Don't do it speculatively.
 *
 * ====================================================================
 *  DRIFT CORRECTION (the math)
 * ====================================================================
 *
 *  The frame budget in nanoseconds is computed once at construction:
 *
 *      nanosPerFrame = 1,000,000,000 / fps
 *
 *  Since 10^9 is not evenly divisible by any of our rates, the budget
 *  is rounded. The rounding error per frame:
 *
 *      FPS   exact nanos/frame    integer nanos/frame   drift/frame
 *      ---   ----------------    -------------------   ----------
 *      30    33,333,333.33…      33,333,333            0.33 ns
 *      60    16,666,666.66…      16,666,666            0.67 ns
 *      120   8,333,333.33…       8,333,333             0.33 ns
 *
 *  Naive additive wait (nextDeadline += budget) accumulates this
 *  error. At 60 fps: 0.67 × 60 = 40 ns/sec. Not visible, but breaks
 *  P2P lockstep determinism (peer A and peer B at the same tic
 *  number are at slightly different "real" times).
 *
 *  Correct approach: compute the deadline ABSOLUTELY from a fixed
 *  origin on every iteration:
 *
 *      deadline_nanos = startNanos + (tic * nanosPerFrame)
 *
 *  The rounding error is now symmetric and bounded — two machines
 *  running this code at the same time reach the same deadline at
 *  the same tic. This is the math behind deterministic netcode.
 *
 *  See: Glenn Fiedler, "Fix Your Timestep" —
 *  https://gafferongames.com/post/fix_your_timestep/
 *
 * ====================================================================
 *  LONG OVERFLOW
 * ====================================================================
 *
 *  At 120 fps, nanosPerFrame = 8,333,333. Overflow of `tic * nanosPerFrame`
 *  happens at tic = 9.2 × 10^18 / 8.3 × 10^6 ≈ 1.1 × 10^12 tics, or
 *  about 290 years of continuous 120 fps runtime. We don't worry.
 */
public enum FrameRate
{
    /** 30 Hz — console target, low-power mode. */
    FPS_30(30),

    /** 60 Hz — default for desktop and headless tests. */
    FPS_60(60),

    /** 120 Hz — high-refresh gaming displays. */
    FPS_120(120);

    private final int fps;
    private final long nanosPerFrame;

    FrameRate(final int fps)
    {
        this.fps = fps;
        this.nanosPerFrame = 1_000_000_000L / fps;
    }

    /** Returns the frame rate in Hz. */
    public int fps()
    {
        return fps;
    }

    /**
     * Returns the frame budget in nanoseconds (integer; rounds down
     * from the exact value, leaving a sub-nanosecond drift per frame —
     * see class Javadoc for the drift-correction strategy).
     */
    public long nanosPerFrame()
    {
        return nanosPerFrame;
    }

    /** Returns the frame budget in microseconds, for logging. */
    public long microsPerFrame()
    {
        return nanosPerFrame / 1000L;
    }

    /**
     * Parses a string to a {@code FrameRate}. Accepts:
     *   "30", "60", "120"        — numeric form
     *   "FPS_30", "FPS_60", "FPS_120" — enum-name form
     *
     * Case-insensitive. Anything else throws {@link IllegalArgumentException}.
     *
     * @param s the input string
     * @return the matching rate
     * @throws IllegalArgumentException if s is null or doesn't match a known rate
     */
    public static FrameRate fromString(final String s)
    {
        if (s == null)
        {
            throw new IllegalArgumentException("frame rate string must not be null");
        }
        final String normalized = s.trim().toUpperCase();
        for (final FrameRate r : values())
        {
            if (r.name().equals(normalized))
            {
                return r;
            }
            if (String.valueOf(r.fps).equals(normalized))
            {
                return r;
            }
        }
        throw new IllegalArgumentException(
            "unknown frame rate: '" + s + "' (supported: 30, 60, 120)");
    }
}
