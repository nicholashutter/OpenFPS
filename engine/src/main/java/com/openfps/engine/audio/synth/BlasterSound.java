/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

/**
 * S_ The blaster's firing sound, generated from arithmetic — there is no file.
 *
 * <h2>Why this exists instead of a .wav in the repository</h2>
 *
 * <p>{@code docs/ASSETS.md} § 3 has an accepted-sources table with <b>no audio
 * row in it</b>, and {@code audio/README.md} recorded the source, format and
 * container questions as deliberately open rather than answering them by
 * accident. Shipping a sound file would have forced all three at once —
 * where it came from, what licence it carries, how it reaches the runtime —
 * for one blaster noise.</p>
 *
 * <p>Generating it sidesteps every one of them. There is no third-party
 * content, so there is nothing to add to {@code NOTICE}; no binary in git, so
 * the repository's "ships no game data in source form" rule is untouched; no
 * staging step, so a fresh clone with no {@code assets/} directory still makes
 * a noise. The three open questions in {@code audio/README.md} stay open and
 * stay honest — they are the right questions for a <i>sound bank</i>, and this
 * is not one.</p>
 *
 * <h2>The signal</h2>
 *
 * <p>A descending pitch sweep with a fast exponential decay: the sound a
 * cartoon blaster makes, chosen because it is recognisable from arithmetic
 * alone. 900 Hz falling to 120 Hz over 180 ms, a sine plus a quiet third
 * harmonic for edge, 22.05 kHz mono 16-bit.</p>
 *
 * <h2>Two pieces of arithmetic that are easy to get wrong</h2>
 *
 * <p><b>1. The sweep integrates frequency; it does not substitute it.</b> The
 * intuitive line is {@code sin(2π · f(t) · t)}, and it produces the wrong
 * sound. Instantaneous frequency is the <i>derivative</i> of phase, so writing
 * a time-varying {@code f} inside that expression makes the actual frequency
 * {@code f(t) + t·f'(t)} — for a falling sweep the second term is negative and
 * large, and the pitch dives far below where it was asked to, hitting zero and
 * running backwards. The fix is to accumulate: {@code phase += 2π·f(i)/rate}
 * per sample, which is what {@link #samples()} does. It is a classic enough
 * error to have a name (the "chirp phase" bug) and it sounds like a broken
 * synthesiser rather than like a mistake.</p>
 *
 * <p><b>2. Both ends of the envelope are forced to zero.</b> A waveform that
 * starts or stops mid-cycle is a step discontinuity, and a step contains every
 * frequency — it is heard as a click on top of the sound, loudest on good
 * speakers. The 2 ms attack and 8 ms release ramps exist for that and nothing
 * else; the decay alone does not reach zero at 180 ms
 * ({@code exp(-14 × 0.18) ≈ 0.08}, which is a very audible click). The first
 * and last sample are therefore exactly zero, and the tests assert it.</p>
 *
 * <p>Not {@code StrictMath}: audio is not lockstep, and
 * {@code I_AudioPort} says so. Two peers may differ in the last bit of a sine
 * and nothing observes it, because no tic ever reads a sample back.</p>
 */
public final class BlasterSound
{
    /**
     * Samples per second.
     *
     * <p>22.05 kHz rather than 44.1: the top of this sound is 900 Hz plus a
     * third harmonic at 2.7 kHz, so the Nyquist limit of 11 kHz is over three
     * octaves of headroom above anything present. The higher rate would double
     * the buffer to say the same thing.</p>
     */
    public static final int SAMPLE_RATE = 22050;

    /** Length of the whole sound, in milliseconds. */
    public static final int DURATION_MS = 180;

    /** Where the pitch sweep starts, in Hz. */
    public static final double START_HZ = 900.0;

    /** Where the pitch sweep ends, in Hz. */
    public static final double END_HZ = 120.0;

    /**
     * Exponential amplitude decay, in nepers per second.
     *
     * <p>14 leaves the sound at {@code exp(-14 × 0.18) ≈ 8%} of peak when the
     * release ramp takes over, which is quiet enough that the ramp is not
     * itself audible as a cut.</p>
     */
    public static final double DECAY_PER_SECOND = 14.0;

    /** Peak amplitude as a fraction of full scale — headroom against clipping. */
    public static final double PEAK = 0.72;

    /** Relative level of the third harmonic that gives the sweep its edge. */
    private static final double THIRD_HARMONIC = 0.25;

    /** Relative level of the fundamental. Sums with the harmonic to 1. */
    private static final double FUNDAMENTAL = 0.75;

    /** Attack ramp length in samples — 2 ms. */
    private static final int ATTACK_SAMPLES = SAMPLE_RATE * 2 / 1000;

    /** Release ramp length in samples — 8 ms. */
    private static final int RELEASE_SAMPLES = SAMPLE_RATE * 8 / 1000;

    /** Milliseconds in a second. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** One full turn, in radians. */
    private static final double TWO_PI = 2.0 * Math.PI;

    private BlasterSound()
    {
        // synthesis holder
    }

    /**
     * Returns the sample rate the generated buffer is meant to be played at.
     *
     * @return samples per second
     */
    public static int sampleRate()
    {
        return SAMPLE_RATE;
    }

    /**
     * Returns how many samples {@link #samples()} produces.
     *
     * @return the sample count, always positive
     */
    public static int sampleCount()
    {
        return (int) (SAMPLE_RATE * DURATION_MS / MILLIS_PER_SECOND);
    }

    /**
     * Returns the instantaneous frequency at one sample index, in Hz.
     *
     * <p>An <b>exponential</b> sweep — constant musical interval per unit time
     * — rather than a linear one. A linear ramp from 900 Hz to 120 Hz spends
     * most of its length in the top octave and crosses the bottom four in the
     * last few milliseconds, which is heard as a thump with a squeak on the
     * front rather than as a fall. Pitch perception is logarithmic, so a sweep
     * that sounds even has to be geometric.</p>
     *
     * @param index the sample index; values outside the buffer extrapolate the
     *     same curve rather than being rejected, which is what makes this
     *     testable at the endpoints
     * @return the frequency at that sample, in Hz
     */
    public static double frequencyAt(final int index)
    {
        final double progress = index / (double) sampleCount();

        return START_HZ * Math.pow(END_HZ / START_HZ, progress);
    }

    /**
     * Returns the amplitude envelope at one sample index, in {@code [0, 1]}.
     *
     * <p>Three factors multiplied: a linear attack out of silence, the
     * exponential body decay, and a linear release back into silence. The
     * ramps are the anti-click measure described in the class Javadoc — the
     * envelope is exactly 0 at the first and last sample by construction, not
     * by luck.</p>
     *
     * @param index the sample index
     * @return the envelope value, in {@code [0, 1]}
     */
    public static double envelopeAt(final int index)
    {
        final int count = sampleCount();

        if (index < 0 || index >= count)
        {
            return 0.0;
        }

        final double attack = Math.min(1.0, index / (double) ATTACK_SAMPLES);

        final double release = Math.min(1.0, (count - 1 - index) / (double) RELEASE_SAMPLES);

        final double decay = Math.exp(-DECAY_PER_SECOND * index / SAMPLE_RATE);

        return attack * decay * release;
    }

    /**
     * Generates the whole sound as signed 16-bit mono PCM.
     *
     * <p>Allocates a fresh array each call — about 8 KB — and is expected to be
     * called once per process, when an adapter bakes its sounds. It is not on
     * any per-tic or per-frame path, so {@code STYLE.md} § 13.4's
     * no-runtime-allocation rule for the mix path does not reach it.</p>
     *
     * @return a fresh buffer of {@link #sampleCount()} samples
     */
    public static short[] samples()
    {
        final int count = sampleCount();

        final short[] pcm = new short[count];

        // Phase is ACCUMULATED, not evaluated — see the class Javadoc. This
        // single line is the difference between a blaster and a broken
        // synthesiser.
        double phase = 0.0;

        for (int index = 0; index < count; index++)
        {
            phase = phase + TWO_PI * frequencyAt(index) / SAMPLE_RATE;

            final double wave = FUNDAMENTAL * Math.sin(phase)
                + THIRD_HARMONIC * Math.sin(3.0 * phase);

            final double value = PEAK * envelopeAt(index) * wave * Short.MAX_VALUE;

            // Clamped rather than trusted. The fundamental and the harmonic can
            // align to slightly more than the sum of their coefficients, and a
            // cast that wraps turns a loud sample into an equally loud sample
            // of the opposite sign — which is a click, not a clip.
            pcm[index] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(value)));
        }

        return pcm;
    }
}
