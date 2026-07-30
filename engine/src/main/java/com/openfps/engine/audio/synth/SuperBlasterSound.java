/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

/**
 * S_ The super blaster firing — the player's own weapon with an octave of weight
 * added, generated from arithmetic.
 *
 * <p>{@link BlasterSound}'s argument for generating rather than shipping a
 * {@code .wav} applies unchanged and is not repeated: no third-party content, no
 * binary in git, no staging step, nothing to add to {@code NOTICE}.</p>
 *
 * <h2>The requirement here is the OPPOSITE of the carbine's</h2>
 *
 * <p>{@link CarbineSound} exists to be told apart from the player's weapon, and
 * its Javadoc argues at length that the two therefore have to sit in different
 * families — a tone against a noise burst. <b>This one has to be recognised as
 * the same weapon.</b> The player has held the trigger down for four seconds and
 * heard the ordinary blaster five times a second for the whole round before that;
 * a reward that replaced the sound outright would read as picking up a different
 * gun, which is not what happened, and it would make the moment the buff expires
 * feel like losing a weapon rather than like a timer running out.</p>
 *
 * <p>So this is built <b>out of</b> the blaster rather than beside it. One phase
 * accumulator runs an octave below {@link BlasterSound}'s sweep, and three voices
 * are read off it:</p>
 *
 * <ul>
 *   <li>the <b>sub-octave</b> at 1x — 450 Hz falling to 60, the weight that is
 *       new;</li>
 *   <li>the <b>ordinary blaster's own pitch</b> at 2x — 900 Hz falling to 120,
 *       which is exactly {@link BlasterSound#START_HZ} to
 *       {@link BlasterSound#END_HZ}, because the frequencies here are
 *       <i>derived</i> from those constants rather than written down again;</li>
 *   <li>its <b>third harmonic</b> at 6x, the edge the ordinary blaster already
 *       has.</li>
 * </ul>
 *
 * <p>The result contains the old sound and adds to it, which is what "upgraded"
 * has to mean if the ear is to place it. The three separations that make it
 * <i>bigger</i> are each a number below rather than an adjective: it is
 * <b>longer</b> ({@link #DURATION_MS} against 180), it <b>rings on</b>
 * ({@link #DECAY_PER_SECOND} against 14) and it is <b>louder</b>
 * ({@link #PEAK} against 0.72), because it is the one thing in the mix the player
 * has just earned.</p>
 *
 * <h2>The arithmetic that is easy to get wrong</h2>
 *
 * <p>Both of {@link BlasterSound}'s traps are present unchanged and its Javadoc
 * has them in full. <b>The sweep integrates frequency rather than substituting
 * it</b> — a time-varying {@code f} inside {@code sin(2π f t)} makes the actual
 * pitch {@code f + t f'}, which dives below zero and runs backwards — and
 * <b>both ends of the envelope are forced to zero</b>, because a waveform that
 * starts or stops mid-cycle is a step and a step is heard as a click.
 * {@code exp(-9 × 0.26)} is about 10% of peak, so the release ramp is
 * load-bearing here too.</p>
 *
 * <p>Not {@code StrictMath}: audio is not lockstep, and no tic ever reads a
 * sample back.</p>
 */
public final class SuperBlasterSound
{
    /** Samples per second — {@link BlasterSound#SAMPLE_RATE}, so one rate serves all. */
    public static final int SAMPLE_RATE = BlasterSound.SAMPLE_RATE;

    /**
     * Length of the whole sound, in milliseconds — <b>260</b>.
     *
     * <p>Half again the blaster's 180. Long enough that the extra weight has time
     * to be heard, and still comfortably under the weapon's 12-tic cooldown —
     * 200 ms at 60 Hz — so a held trigger does not stack copies of it. That
     * bound matters: the player's own weapon has no voice gate, because its rate
     * of fire is its gate.</p>
     */
    public static final int DURATION_MS = 260;

    /**
     * How far below the ordinary blaster this sound's fundamental sits — <b>2</b>,
     * one octave.
     *
     * <p>Named so the two sweep endpoints can be <i>derived</i> from
     * {@link BlasterSound}'s. Written out as literals they would be two more
     * numbers to keep in step with a sound they are supposed to be a version
     * of.</p>
     */
    public static final double SUB_OCTAVE_DIVISOR = 2.0;

    /** Where the sweep starts, in Hz — an octave below the blaster's 900. */
    public static final double START_HZ = BlasterSound.START_HZ / SUB_OCTAVE_DIVISOR;

    /** Where the sweep ends, in Hz — an octave below the blaster's 120. */
    public static final double END_HZ = BlasterSound.END_HZ / SUB_OCTAVE_DIVISOR;

    /**
     * Exponential amplitude decay, in nepers per second — <b>9</b>.
     *
     * <p>Slower than the blaster's 14, which is what makes this ring rather than
     * snap. It leaves about 10% of peak when the release ramp takes over, close
     * to the blaster's 8%, so the ramp is not itself audible as a cut.</p>
     */
    public static final double DECAY_PER_SECOND = 9.0;

    /**
     * Peak amplitude as a fraction of full scale — <b>0.85</b>.
     *
     * <p>Above {@link BlasterSound#PEAK} of 0.72, deliberately: this is the one
     * thing in the mix the player has just earned, and a reward that was quieter
     * than the weapon it replaces would be an odd sort of reward. Still short of
     * full scale, and the voice levels below sum to exactly 1, so the loudest
     * possible sample is 0.85 of full scale rather than a clip.</p>
     */
    public static final double PEAK = 0.85;

    /** Relative level of the new sub-octave — the weight. */
    private static final double SUB_LEVEL = 0.45;

    /** Relative level of the ordinary blaster's own pitch, an octave up. */
    private static final double BLASTER_LEVEL = 0.40;

    /** Relative level of the blaster's third harmonic — the edge. */
    private static final double EDGE_LEVEL = 0.15;

    /** Multiple of the fundamental that reproduces the ordinary blaster's pitch. */
    private static final double BLASTER_MULTIPLE = SUB_OCTAVE_DIVISOR;

    /** Multiple of the fundamental that reproduces the blaster's third harmonic. */
    private static final double EDGE_MULTIPLE = 3.0 * SUB_OCTAVE_DIVISOR;

    /** Attack ramp length in samples — 2 ms, as the blaster's. */
    private static final int ATTACK_SAMPLES = SAMPLE_RATE * 2 / 1000;

    /** Release ramp length in samples — 8 ms, as the blaster's. */
    private static final int RELEASE_SAMPLES = SAMPLE_RATE * 8 / 1000;

    /** Milliseconds in a second. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** One full turn, in radians. */
    private static final double TWO_PI = 2.0 * Math.PI;

    private SuperBlasterSound()
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
     * Returns the instantaneous fundamental frequency at one sample index, in Hz.
     *
     * <p>An <b>exponential</b> sweep, for the reason
     * {@link BlasterSound#frequencyAt} gives: pitch perception is logarithmic, so
     * a linear ramp spends most of its length in the top octave and crosses the
     * bottom four in the last few milliseconds — heard as a thump with a squeak
     * on the front rather than as a fall.</p>
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
     * <p>A linear attack out of silence, an exponential body decay, and a linear
     * release back into silence. Exactly 0 at the first and last sample by
     * construction — see the class Javadoc on why that is mandatory rather than
     * tidy.</p>
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
     * <p>Allocates a fresh array each call — about 11 KB — and is expected to be
     * called once per process, when an adapter bakes its sounds. Not on any
     * per-tic or per-frame path, so {@code STYLE.md} § 13.4's no-allocation rule
     * for the mix path does not reach it.</p>
     *
     * @return a fresh buffer of {@link #sampleCount()} samples
     */
    public static short[] samples()
    {
        final int count = sampleCount();
        final short[] pcm = new short[count];
        // Phase is ACCUMULATED at the FUNDAMENTAL, and the other two voices are
        // read off multiples of it rather than accumulated separately. That is what
        // keeps them locked: three independent accumulators would drift apart by a
        // rounding error per sample and the chord would slowly detune over 260 ms.
        double phase = 0.0;
        for (int index = 0; index < count; index++)
        {
            phase = phase + TWO_PI * frequencyAt(index) / SAMPLE_RATE;
            final double wave = SUB_LEVEL * Math.sin(phase)
                + BLASTER_LEVEL * Math.sin(BLASTER_MULTIPLE * phase)
                + EDGE_LEVEL * Math.sin(EDGE_MULTIPLE * phase);
            final double value = PEAK * envelopeAt(index) * wave * Short.MAX_VALUE;
            // Clamped rather than trusted, for the reason BlasterSound gives: a
            // cast that wraps turns a loud sample into an equally loud sample of
            // the opposite sign, which is a click and not a clip.
            pcm[index] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(value)));
        }
        return pcm;
    }
}
