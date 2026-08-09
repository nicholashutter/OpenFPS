/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

/**
 * S_ The bots' carbine firing, generated from arithmetic — there is no file.
 *
 * <p>The second sound in the engine, and it exists for the reason
 * {@code SoundId.BOT_WEAPON_FIRE} gives: incoming fire has to be identifiable by
 * ear alone. {@link BlasterSound}'s argument for generating rather than shipping a
 * {@code .wav} applies unchanged and is not repeated — no third-party content, no
 * binary in git, no staging step, nothing to add to {@code NOTICE}.</p>
 *
 * <h2>Different in KIND, not in degree</h2>
 *
 * <p><b>The obvious move — the player's sweep, transposed down, played quieter —
 * would have failed the requirement while sounding like an answer to it.</b> Two
 * pitch sweeps a few semitones apart are two firings of the same weapon; the ear
 * files them together and reports "a gun went off", which is exactly what the
 * player already knew. What has to be distinguishable is the <i>direction</i> of
 * the fire, so the two sounds have to sit in different families:</p>
 *
 * <ul>
 *   <li><b>The player's is tonal.</b> A 900 Hz sine falling to 120, with a third
 *       harmonic — a pitch, and a pitch you can hum.</li>
 *   <li><b>This is noise.</b> Low-passed pseudo-random samples with a short
 *       {@link #BODY_HZ} thump under them. There is no pitch to hear at all; it is
 *       a dry crack. A tonal sound and a noise burst are told apart
 *       pre-attentively, which is what "at a glance" means for the ears.</li>
 * </ul>
 *
 * <p>Three smaller separations reinforce it, and each is a number below rather
 * than an adjective: it is <b>shorter</b> ({@link #DURATION_MS} against 180), it
 * <b>decays faster</b> ({@link #DECAY_PER_SECOND} against 14), and it is
 * <b>quieter</b> ({@link #PEAK} against 0.72) so that the player's own weapon
 * always sits in front of the room's.</p>
 *
 * <h2>The noise is deterministic, and that is not superstition</h2>
 *
 * <p>Audio is explicitly not lockstep ({@code I_AudioPort}), so nothing here could
 * desync a peer — a sound is a consequence of a tic and no tic reads one back. The
 * noise is still generated from a <b>fixed-seed integer LCG written out in
 * full</b>, not from {@code java.util.Random} and certainly not from
 * {@code Math.random()}, for two reasons that outlive the lockstep argument:</p>
 *
 * <ol>
 *   <li><b>The bake has to be testable.</b> {@code CarbineSoundTest} asserts the
 *       first and last sample are exactly zero, that nothing clips, and that this
 *       sound's spectrum is nothing like the blaster's. None of those are
 *       assertions you can make about a buffer that differs every run.</li>
 *   <li><b>{@code BotRng}'s rule is a flat rule for a reason.</b> "No clock-seeded
 *       randomness in this engine" is enforceable precisely because it has no
 *       exceptions to remember, and an exception carved out here — in a file whose
 *       Javadoc explains at length why it is safe — is exactly the precedent that
 *       makes the next one look reasonable too.</li>
 * </ol>
 *
 * <p>The LCG constants are Numerical Recipes' {@code 1664525 / 1013904223}, a
 * 32-bit multiplicative congruential generator. It is a poor generator for
 * anything that matters and an entirely good one for making a hiss.</p>
 *
 * <h2>The two pieces of arithmetic that are easy to get wrong</h2>
 *
 * <p><b>1. Raw white noise does not sound like a gun; it sounds like a radio.</b>
 * A gunshot's energy is concentrated low. The noise is therefore run through a
 * <b>one-pole low-pass</b> — {@code y += a (x - y)} — with {@link #CUTOFF_HZ} at
 * 1.6 kHz, which is what turns a hiss into a thud. The coefficient is
 * {@code 1 - exp(-2 pi f / rate)} and not {@code f / rate}: the linear form is a
 * first-order approximation that is only close for cutoffs far below the sample
 * rate, and at 1.6 kHz against 22.05 kHz it is out by a fifth.</p>
 *
 * <p><b>2. Both ends of the envelope are forced to zero</b>, for the reason
 * {@link BlasterSound} records at length: a waveform that starts or stops
 * mid-cycle is a step, a step contains every frequency, and it is heard as a click
 * on top of the sound. This decay is faster than the blaster's and still does not
 * reach zero on its own — {@code exp(-28 x 0.12)} is about 3.5% — so the ramps are
 * mandatory here too.</p>
 *
 * <p>Not {@code StrictMath}, for the same reason {@link BlasterSound} is not: two
 * peers may differ in the last bit of a sine and nothing observes it.</p>
 */
public final class CarbineSound
{
    /** Samples per second — {@link BlasterSound#SAMPLE_RATE}, so one mixer rate serves both. */
    public static final int SAMPLE_RATE = BlasterSound.SAMPLE_RATE;

    /**
     * Length of the whole sound, in milliseconds — <b>120</b>.
     *
     * <p>Two thirds of the blaster's 180, which is one of the separations that
     * makes the two identifiable. It is also what bounds how many bot voices can
     * overlap: at 60 Hz this is 7.2 tics, so {@code BotFireVoices}' six-tic
     * minimum interval caps the mix at two concurrent copies by arithmetic rather
     * than by hope.</p>
     */
    public static final int DURATION_MS = 120;

    /**
     * Corner frequency of the low-pass, in Hz — <b>1600</b>.
     *
     * <p>Low enough to be a thud rather than a hiss, high enough that the crack
     * still has an edge on it. Well under the 11 kHz Nyquist limit, so nothing
     * aliases.</p>
     */
    public static final double CUTOFF_HZ = 1600.0;

    /**
     * The low sine under the noise, in Hz — <b>96</b>.
     *
     * <p>Two and a half octaves below where the blaster's sweep <i>ends</i>, so
     * the two do not share a register even where they are both at their lowest.
     * It is a fixed pitch rather than a sweep, which is the other half of not
     * sounding like the player's weapon.</p>
     */
    public static final double BODY_HZ = 96.0;

    /**
     * Exponential amplitude decay, in nepers per second — <b>28</b>.
     *
     * <p>Twice the blaster's, which is what makes this a crack rather than a
     * report. It leaves about 3.5% of peak when the release ramp takes over.</p>
     */
    public static final double DECAY_PER_SECOND = 28.0;

    /**
     * Peak amplitude as a fraction of full scale — <b>0.45</b>.
     *
     * <p>Well under {@link BlasterSound#PEAK} of 0.72, deliberately: the player's
     * own weapon is the loudest thing in the mix because it is the one they
     * control, and return fire is the room behind it. It is also the headroom that
     * makes the voice cap safe — two concurrent copies reach 0.90, which is inside
     * full scale, so the one overlap {@code BotFireVoices} permits cannot
     * clip.</p>
     */
    public static final double PEAK = 0.45;

    /** Relative level of the filtered noise. Sums with {@link #BODY_LEVEL} to 1. */
    private static final double NOISE_LEVEL = 0.68;

    /** Relative level of the low sine that gives the crack its weight. */
    private static final double BODY_LEVEL = 0.32;

    /** Attack ramp length in samples — 1 ms, shorter than the blaster's for a harder onset. */
    private static final int ATTACK_SAMPLES = SAMPLE_RATE / 1000;

    /** Release ramp length in samples — 8 ms, as the blaster's. */
    private static final int RELEASE_SAMPLES = SAMPLE_RATE * 8 / 1000;

    /** Milliseconds in a second. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** One full turn, in radians. */
    private static final double TWO_PI = 2.0 * Math.PI;

    /**
     * Seed for the noise generator — arbitrary but <b>fixed</b>, so the baked
     * buffer is the same on every run and a test can assert things about it.
     */
    private static final int NOISE_SEED = 0x1BADB002;

    /** Numerical Recipes' 32-bit LCG multiplier. */
    private static final int LCG_MULTIPLIER = 1664525;

    /** Numerical Recipes' 32-bit LCG increment. */
    private static final int LCG_INCREMENT = 1013904223;

    /** Bits of an {@code int} kept when a draw is folded to a unit float. */
    private static final int NOISE_SHIFT = 8;

    /** {@code 2^-23}, mapping 24 unsigned bits onto {@code [0, 2)} after doubling. */
    private static final double NOISE_SCALE = 1.0 / (1 << 23);

    private CarbineSound()
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
     * Returns the one-pole low-pass coefficient for {@link #CUTOFF_HZ}.
     *
     * <p>{@code 1 - exp(-2 pi f / rate)}, the exact single-pole response, rather
     * than the {@code f / rate} approximation everyone writes first. Exposed
     * because it is the one line in this file whose value is worth pinning: get it
     * wrong and the sound is a hiss instead of a thud, which is audible and is not
     * a crash.</p>
     *
     * @return the coefficient, in {@code (0, 1)}
     */
    public static double lowPassCoefficient()
    {
        return 1.0 - Math.exp(-TWO_PI * CUTOFF_HZ / SAMPLE_RATE);
    }

    /**
     * Returns the amplitude envelope at one sample index, in {@code [0, 1]}.
     *
     * <p>A hard 1 ms attack, an exponential body decay, and a linear release back
     * into silence. Exactly 0 at the first and last sample by construction — see
     * the class Javadoc on why that is mandatory rather than tidy.</p>
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
     * <p>Allocates a fresh array each call — about 5 KB — and is expected to be
     * called once per process, when an adapter bakes its sounds. Not on any
     * per-tic or per-frame path.</p>
     *
     * @return a fresh buffer of {@link #sampleCount()} samples
     */
    public static short[] samples()
    {
        final int count = sampleCount();

        final short[] pcm = new short[count];

        final double coefficient = lowPassCoefficient();

        // MUTABLE locals — the LCG's state, the filter's memory, and the body's
        // phase. All three are accumulators, which is why they are locals carried
        // through the loop rather than recomputed per sample.
        int noiseState = NOISE_SEED;

        double filtered = 0.0;

        double phase = 0.0;

        for (int index = 0; index < count; index++)
        {
            noiseState = noiseState * LCG_MULTIPLIER + LCG_INCREMENT;

            final double white = unitNoise(noiseState);

            filtered = filtered + coefficient * (white - filtered);

            phase = phase + TWO_PI * BODY_HZ / SAMPLE_RATE;

            final double wave = NOISE_LEVEL * filtered + BODY_LEVEL * Math.sin(phase);

            final double value = PEAK * envelopeAt(index) * wave * Short.MAX_VALUE;

            // Clamped rather than trusted, for the reason BlasterSound gives: a
            // cast that wraps turns a loud sample into an equally loud sample of
            // the opposite sign, which is a click and not a clip.
            pcm[index] = (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(value)));
        }

        return pcm;
    }

    // One LCG draw folded to [-1, 1). The top 24 bits, because an LCG's low bits
    // have famously short periods — the lowest bit of this one alternates 0, 1, 0,
    // 1 forever, which is a 11 kHz tone rather than noise.
    private static double unitNoise(final int state)
    {
        return ((state >>> NOISE_SHIFT) * NOISE_SCALE) - 1.0;
    }
}
