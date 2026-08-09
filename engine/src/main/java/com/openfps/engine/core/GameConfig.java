/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

/**
 * Immutable configuration for a game run.
 *
 * Holds the {@link FrameRate} the engine runs at and the maximum number
 * of tics to run before auto-shutdown. The latter exists for headless
 * CI / smoke tests; in production, maxTics is set to a large value
 * (or {@code Integer.MAX_VALUE}) and shutdown is driven by an external
 * event (UI, signal, or {@code ShutdownEvent}).
 *
 * Construct via factory methods, not the constructor directly, so
 * validation is uniform.
 *
 * Example usage:
 *
 * <pre>
 *     // Default: 60 fps, 120 tics (2-second headless run)
 *     GameConfig config = GameConfig.default60();
 *
 *     // Custom: 120 fps, 240 tics (2-second run at high refresh)
 *     GameConfig config = GameConfig.of(FrameRate.FPS_120, 240);
 *
 *     // Headless: rate × 2 tics (always ~2 seconds regardless of rate)
 *     GameConfig config = GameConfig.headless(FrameRate.FPS_30);
 * </pre>
 */
public final class GameConfig
{
    private final FrameRate rate;
    private final int maxTics;

    private GameConfig(final FrameRate rate, final int maxTics)
    {
        if (rate == null)
        {
            throw new IllegalArgumentException("rate must not be null");
        }

        if (maxTics <= 0)
        {
            throw new IllegalArgumentException("maxTics must be > 0, got " + maxTics);
        }

        this.rate = rate;

        this.maxTics = maxTics;
    }

    /** Constructs a config with explicit rate and max tics. */
    public static GameConfig of(final FrameRate rate, final int maxTics)
    {
        return new GameConfig(rate, maxTics);
    }

    /** Default: 60 fps, 120 tics (≈ 2 seconds of headless runtime). */
    public static GameConfig default60()
    {
        return new GameConfig(FrameRate.FPS_60, FrameRate.FPS_60.fps() * 2);
    }

    /**
     * Headless-friendly config: runs at the given rate for `rate.fps() * 2`
     * tics — exactly 2 seconds of simulated runtime regardless of rate.
     */
    public static GameConfig headless(final FrameRate rate)
    {
        return new GameConfig(rate, rate.fps() * 2);
    }

    /**
     * Unbounded config: runs at the given rate until external shutdown.
     * Used for production launches and manual testing.
     */
    public static GameConfig unbounded(final FrameRate rate)
    {
        return new GameConfig(rate, Integer.MAX_VALUE);
    }

    /** Returns the frame rate. */
    public FrameRate rate()
    {
        return rate;
    }

    /** Returns the maximum tics before auto-shutdown. */
    public int maxTics()
    {
        return maxTics;
    }

    /** Convenience accessor: the frame budget in nanoseconds. */
    public long nanosPerTic()
    {
        return rate.nanosPerFrame();
    }

    @Override
    public String toString()
    {
        return "GameConfig{rate=" + rate + ", maxTics=" + maxTics + "}";
    }
}
