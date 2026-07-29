/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

/**
 * Turns a stream of frame durations into a number a human can read.
 *
 * <h2>Why the raw reciprocal is useless</h2>
 *
 * <p>{@code 1 / lastFrameSeconds}, printed every frame, is unreadable — and not
 * because it is noisy in the abstract. At 60 Hz a frame is 16.7 ms, and a single
 * millisecond of jitter — one garbage collection, one window-manager hiccup, one
 * scheduler slice landing badly — moves the displayed figure by four. The
 * counter spends its life flickering between 56 and 64 and tells you nothing
 * about either. Worse, it flickers <i>most</i> when the frame time is smallest,
 * so the display is least stable exactly when the renderer is healthiest.</p>
 *
 * <h2>The smoothing, and why it is on the frame time</h2>
 *
 * <p>An exponentially weighted moving average:</p>
 *
 * <pre>
 *   average += smoothing * (sample - average)
 * </pre>
 *
 * <p>One multiply and two adds, no history buffer, no ring index, no allocation
 * — which matters because this runs on the render thread of a game, and a
 * diagnostic that costs frames is measuring itself.</p>
 *
 * <p><b>The average is over the frame time, and the frame rate is derived from
 * it.</b> That is the correct order and the other one is a real mistake:
 * averaging the reciprocals answers "the mean of the instantaneous rates", which
 * over-weights the fast frames and reports a number better than the machine
 * actually managed. Averaging the durations and inverting once answers "frames
 * per second at the current pace", which is the question. A run of nineteen 10
 * ms frames and one 210 ms stall took 400 ms for 20 frames — 50 fps. This
 * reports 50. Averaging rates reports 95.</p>
 *
 * <h2>The first sample is taken whole</h2>
 *
 * <p>Seeding the average at zero would make the counter climb toward the truth
 * over the first second of every run and of every unpause, which reads as a
 * performance problem that is not there. The first sample <i>is</i> the average;
 * smoothing begins with the second.</p>
 *
 * <p>This class imports nothing from any toolkit, which is the point: the
 * arithmetic is the part that can be wrong, and it is fully testable in a
 * headless JVM. Drawing the result is {@link DebugOverlay}'s job.</p>
 *
 * <p><b>Threading:</b> not thread-safe. One meter belongs to one thread — the
 * platform's render thread — which is the only thread that sees a frame begin
 * and end.</p>
 */
public final class FpsMeter
{
    /**
     * Default weight given to the newest sample — <b>0.1</b>.
     *
     * <p>The average then has a time constant of about ten frames, a sixth of a
     * second at 60 Hz. Fast enough that dropping to 30 fps is visible almost
     * immediately, slow enough that a single late frame moves the display by
     * a tenth of its error rather than all of it.</p>
     *
     * <p>Larger values chase noise — at 0.5 the counter is nearly as jumpy as
     * the raw reciprocal. Smaller ones lag: at 0.01 a genuine collapse to 20 fps
     * takes over a second to show, by which time the thing you were trying to
     * catch has scrolled off.</p>
     */
    public static final float DEFAULT_SMOOTHING = 0.1f;

    /** Nanoseconds in a millisecond. */
    private static final float NANOS_PER_MILLI = 1_000_000.0f;

    /** Milliseconds in a second, for turning a frame time into a rate. */
    private static final float MILLIS_PER_SECOND = 1000.0f;

    /** Weight given to each new sample. Between 0 (exclusive) and 1 (inclusive). */
    private final float smoothing;

    /**
     * The smoothed frame time in milliseconds.
     * MUTABLE: advanced by {@link #sample}, which is the whole job.
     */
    private float frameMillis;

    /**
     * How many samples have been folded in.
     * MUTABLE: bumped by {@link #sample}. Doubles as the "has the average been
     * seeded" flag, so there is no second field that could disagree with it.
     */
    private long samples;

    /** Creates a meter smoothing at {@link #DEFAULT_SMOOTHING}. */
    public FpsMeter()
    {
        this(DEFAULT_SMOOTHING);
    }

    /**
     * Creates a meter with an explicit smoothing weight.
     *
     * @param sampleWeight how much of each new sample to take, in {@code (0, 1]}.
     *     1 disables smoothing entirely and reports the last frame, which is
     *     what a test wants and never what a display does
     * @throws IllegalArgumentException if {@code sampleWeight} is not in range,
     *     which includes NaN — the comparison is written so that NaN fails it
     */
    public FpsMeter(final float sampleWeight)
    {
        if (!(sampleWeight > 0.0f) || !(sampleWeight <= 1.0f))
        {
            throw new IllegalArgumentException(
                "sampleWeight must be in (0, 1], got " + sampleWeight);
        }
        this.smoothing = sampleWeight;
    }

    /**
     * Folds one frame duration into the average.
     *
     * <p><b>Non-positive durations are ignored rather than averaged in.</b> They
     * are not measurements: a renderer that has not finished a frame yet reports
     * zero, and a clock read across a suspend can come back negative. Either one
     * folded in as "a frame that took no time" would spike the reported rate to
     * something impossible, which is worse than showing nothing — the counter
     * exists to be believed.</p>
     *
     * @param frameNanos how long the frame took, in nanoseconds; zero or less is
     *     discarded
     */
    public void sample(final long frameNanos)
    {
        if (frameNanos <= 0L)
        {
            return;
        }
        final float millis = frameNanos / NANOS_PER_MILLI;
        if (samples == 0L)
        {
            // Taken whole. See the class Javadoc: seeding at zero would make
            // every run open with a counter climbing out of a hole it invented.
            this.frameMillis = millis;
        }
        else
        {
            this.frameMillis = frameMillis + smoothing * (millis - frameMillis);
        }
        this.samples = samples + 1L;
    }

    /**
     * Returns the smoothed frame time in milliseconds.
     *
     * @return the average, or 0 before the first accepted sample
     */
    public float frameMillis()
    {
        return frameMillis;
    }

    /**
     * Returns the smoothed frame rate in frames per second.
     *
     * <p>Derived from {@link #frameMillis()} rather than separately averaged —
     * see the class Javadoc for why that ordering is the correct one and not
     * merely the cheaper one.</p>
     *
     * @return frames per second, or 0 before the first accepted sample
     */
    public float fps()
    {
        if (!(frameMillis > 0.0f))
        {
            return 0.0f;
        }
        return MILLIS_PER_SECOND / frameMillis;
    }

    /** Returns how many samples have been folded in. */
    public long samples()
    {
        return samples;
    }

    /** Returns whether the meter has anything to report yet. */
    public boolean hasReading()
    {
        return samples > 0L;
    }

    /** Returns the weight each new sample is given. */
    public float smoothing()
    {
        return smoothing;
    }

    /**
     * Forgets everything measured so far.
     *
     * <p>For the seam between two things worth measuring separately — leaving a
     * menu for the world, say. Carrying the menu's frame times into the world's
     * average would show a rate neither of them ran at.</p>
     */
    public void reset()
    {
        this.frameMillis = 0.0f;
        this.samples = 0L;
    }

    /** Returns a debug rendering of the current reading. */
    @Override
    public String toString()
    {
        return "FpsMeter{" + fps() + " fps, " + frameMillis + " ms, n=" + samples + "}";
    }
}
