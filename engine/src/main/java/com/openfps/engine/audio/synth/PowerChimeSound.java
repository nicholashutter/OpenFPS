/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

/**
 * S_ The two-note chime that says the super blaster has arrived, and the same
 * chime backwards when it goes away. Generated from arithmetic.
 *
 * <p>{@link BlasterSound}'s argument for generating rather than shipping a
 * {@code .wav} applies unchanged and is not repeated.</p>
 *
 * <h2>Why one class for two sounds</h2>
 *
 * <p>Because they are one sound played in two directions, and that is the whole
 * point of them. A reward and its expiry are the same event with opposite signs,
 * so <b>rising</b> and <b>falling</b> is the entire distinction the player has to
 * hear — and it is a distinction the ear makes pre-attentively, without being
 * told which is which. Two separate classes would have let the two drift into
 * being merely different noises, at which point the player has to learn a pair of
 * arbitrary sounds instead of recognising a direction.</p>
 *
 * <p>{@link #readySamples()} is {@link #LOW_HZ} then {@link #HIGH_HZ};
 * {@link #spentSamples()} is the reverse, and quieter — see
 * {@link #SPENT_PEAK}.</p>
 *
 * <h2>Different in KIND from both weapons, which is the requirement</h2>
 *
 * <p>The engine already has a tonal pitch sweep ({@link BlasterSound}), a noise
 * crack ({@link CarbineSound}) and a heavier sweep
 * ({@link SuperBlasterSound}) — three sounds that all begin with a bang and get
 * quieter. This is <b>two steady pitches with a step between them</b>: nothing
 * glides, and the pitch changes exactly once, in the middle. That is why it does
 * not have to compete for attention with the weapons around it; it is not the
 * same class of event, and it says so before it says anything else.</p>
 *
 * <p>The interval is a perfect fifth, {@link #HIGH_HZ} being 3:2 of
 * {@link #LOW_HZ} — the most consonant interval that is not the same note, so two
 * notes are unmistakably two notes and unmistakably related.</p>
 *
 * <h2>The arithmetic that is easy to get wrong</h2>
 *
 * <p><b>Every note ends at exactly zero, not just the sound.</b> The pitch jumps
 * at the boundary between the two notes, and a jump in pitch while the waveform
 * is at nonzero amplitude is a step discontinuity — the same click
 * {@link BlasterSound} forces its endpoint ramps to prevent, except in the
 * <i>middle</i> of the sound where it is even more obvious. So the envelope here
 * is a function of the index <b>within a note</b> rather than within the buffer,
 * and it reaches zero at both ends of each of them. The two notes are exactly the
 * same length, which is what makes {@link #sampleCount()} a single number that
 * both variants share.</p>
 *
 * <p>Not {@code StrictMath}: audio is not lockstep, and no tic ever reads a
 * sample back.</p>
 */
public final class PowerChimeSound
{
    /** Samples per second — {@link BlasterSound#SAMPLE_RATE}, so one rate serves all. */
    public static final int SAMPLE_RATE = BlasterSound.SAMPLE_RATE;

    /** How many notes the chime has — <b>2</b>. One would have no direction to hear. */
    public static final int NOTE_COUNT = 2;

    /**
     * Length of one note, in milliseconds — <b>90</b>.
     *
     * <p>Long enough to be a pitch rather than a click — 90 ms of 660 Hz is about
     * sixty cycles — and short enough that the pair is over in
     * {@link #DURATION_MS}, which is less than a fifth of the reward's window.
     * A confirmation that outlasts the thing it is confirming is an
     * interruption.</p>
     */
    public static final int NOTE_MS = 90;

    /** Length of the whole chime, in milliseconds — both notes. */
    public static final int DURATION_MS = NOTE_MS * NOTE_COUNT;

    /**
     * The lower of the two notes, in Hz — <b>660</b>.
     *
     * <p>Above the blaster's {@link BlasterSound#END_HZ} and below its
     * {@link BlasterSound#START_HZ}, which is deliberate: a chime that sat outside
     * the weapon's register entirely would sound like a different game's UI. It
     * belongs to the same instrument family and is told apart by <i>shape</i>
     * rather than by register.</p>
     */
    public static final double LOW_HZ = 660.0;

    /** Ratio between the two notes — <b>1.5</b>, a perfect fifth. */
    public static final double FIFTH_RATIO = 1.5;

    /** The higher of the two notes, in Hz — a perfect fifth above {@link #LOW_HZ}. */
    public static final double HIGH_HZ = LOW_HZ * FIFTH_RATIO;

    /**
     * Peak amplitude of the award chime, as a fraction of full scale — <b>0.62</b>.
     *
     * <p>Under {@link BlasterSound#PEAK}, because it plays <i>on top of</i> the
     * shot that earned it — the kill's own weapon noise is in the mix on the same
     * tic — and the sum of the two has to stay inside full scale.</p>
     */
    public static final double READY_PEAK = 0.62;

    /**
     * Peak amplitude of the expiry chime — <b>0.42</b>.
     *
     * <p>Quieter than {@link #READY_PEAK} on purpose, and the asymmetry is the
     * design rather than an accident of taste. Missing the award means not knowing
     * you have a better gun; missing the expiry means at worst being told
     * something the on-screen countdown has already spent four seconds telling
     * you. The louder sound is the one carrying information the player does not
     * otherwise have.</p>
     */
    public static final double SPENT_PEAK = 0.42;

    /**
     * Exponential amplitude decay within one note, in nepers per second — <b>8</b>.
     *
     * <p>Gentler than any of the weapons, which is what makes each note a struck
     * bell rather than a shot. {@code exp(-8 × 0.09)} is about 49%, so a note is
     * still half its peak when its release ramp takes over — the ramp is what
     * closes it, and it has to be, which is the point of the class Javadoc's note
     * about the boundary.</p>
     */
    public static final double DECAY_PER_SECOND = 8.0;

    /** Relative level of the note itself. Sums with {@link #SECOND_HARMONIC} to 1. */
    private static final double FUNDAMENTAL = 0.82;

    /** Relative level of the octave above, which gives the note a little brightness. */
    private static final double SECOND_HARMONIC = 0.18;

    /** Multiple of the note that {@link #SECOND_HARMONIC} sits at. */
    private static final double SECOND_HARMONIC_MULTIPLE = 2.0;

    /** Attack ramp length in samples — 3 ms, softer than any weapon's onset. */
    private static final int ATTACK_SAMPLES = SAMPLE_RATE * 3 / 1000;

    /** Release ramp length in samples — 8 ms, as every other sound here. */
    private static final int RELEASE_SAMPLES = SAMPLE_RATE * 8 / 1000;

    /** Milliseconds in a second. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** One full turn, in radians. */
    private static final double TWO_PI = 2.0 * Math.PI;

    private PowerChimeSound()
    {
        // synthesis holder
    }

    /**
     * Returns the sample rate the generated buffers are meant to be played at.
     *
     * @return samples per second
     */
    public static int sampleRate()
    {
        return SAMPLE_RATE;
    }

    /**
     * Returns how many samples one note holds.
     *
     * @return the per-note sample count, always positive
     */
    public static int noteSampleCount()
    {
        return (int) (SAMPLE_RATE * NOTE_MS / MILLIS_PER_SECOND);
    }

    /**
     * Returns how many samples either variant produces.
     *
     * <p>Derived from {@link #noteSampleCount()} rather than from
     * {@link #DURATION_MS}, so the buffer is exactly {@link #NOTE_COUNT} whole
     * notes with no rounding remainder at the end. A leftover sample or two would
     * be silence at nonzero index — harmless, and exactly the sort of thing that
     * makes a length assertion mysterious.</p>
     *
     * @return the sample count, the same for both variants
     */
    public static int sampleCount()
    {
        return noteSampleCount() * NOTE_COUNT;
    }

    /**
     * Returns the amplitude envelope at one sample index <b>within a note</b>, in
     * {@code [0, 1]}.
     *
     * <p>Within a note, not within the buffer — see the class Javadoc. Exactly 0
     * at the first and last sample of every note, which is what stops the pitch
     * step in the middle from being a click.</p>
     *
     * @param indexInNote the sample index within one note
     * @return the envelope value, in {@code [0, 1]}
     */
    public static double envelopeAt(final int indexInNote)
    {
        final int count = noteSampleCount();
        if (indexInNote < 0 || indexInNote >= count)
        {
            return 0.0;
        }
        final double attack = Math.min(1.0, indexInNote / (double) ATTACK_SAMPLES);
        final double release =
            Math.min(1.0, (count - 1 - indexInNote) / (double) RELEASE_SAMPLES);
        final double decay = Math.exp(-DECAY_PER_SECOND * indexInNote / SAMPLE_RATE);
        return attack * decay * release;
    }

    /**
     * Generates the award chime — low note then high — as signed 16-bit mono PCM.
     *
     * <p>Allocates a fresh array each call, about 8 KB, once per process when an
     * adapter bakes.</p>
     *
     * @return a fresh buffer of {@link #sampleCount()} samples
     */
    public static short[] readySamples()
    {
        return chime(LOW_HZ, HIGH_HZ, READY_PEAK);
    }

    /**
     * Generates the expiry chime — high note then low, and quieter.
     *
     * @return a fresh buffer of {@link #sampleCount()} samples
     */
    public static short[] spentSamples()
    {
        return chime(HIGH_HZ, LOW_HZ, SPENT_PEAK);
    }

    // Two notes, one after the other, each ending in silence.
    //
    // The phase accumulator is reset per note rather than carried across the
    // boundary. It costs nothing to do either — both ends of a note are at zero
    // amplitude, so a phase discontinuity there is inaudible — and starting each
    // note at phase zero means a note's samples depend only on its own frequency,
    // which is what lets a test assert the two variants are reverses of each other.
    private static short[] chime(final double firstHz, final double secondHz,
        final double peak)
    {
        final int perNote = noteSampleCount();
        final short[] pcm = new short[perNote * NOTE_COUNT];
        writeNote(pcm, 0, firstHz, peak);
        writeNote(pcm, perNote, secondHz, peak);
        return pcm;
    }

    // One note into a slice of the buffer.
    private static void writeNote(final short[] pcm, final int offset, final double hz,
        final double peak)
    {
        final int perNote = noteSampleCount();
        // MUTABLE local — the accumulated phase. Accumulated rather than evaluated
        // for the reason BlasterSound documents; it matters less at a fixed pitch,
        // and doing it the same way everywhere is why nobody has to check which
        // file made the exception.
        double phase = 0.0;
        for (int index = 0; index < perNote; index++)
        {
            phase = phase + TWO_PI * hz / SAMPLE_RATE;
            final double wave = FUNDAMENTAL * Math.sin(phase)
                + SECOND_HARMONIC * Math.sin(SECOND_HARMONIC_MULTIPLE * phase);
            final double value = peak * envelopeAt(index) * wave * Short.MAX_VALUE;
            pcm[offset + index] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(value)));
        }
    }
}
