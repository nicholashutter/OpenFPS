/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reward chime and its reverse.
 *
 * <p><b>The property that carries the meaning is the DIRECTION</b>, and it is the
 * one nobody could check by reading the code: {@code chime(LOW, HIGH)} and
 * {@code chime(HIGH, LOW)} are the same call with its arguments swapped, so a
 * swap in the wrong place would compile, bake, load and play — announcing the
 * reward as it ran out and its expiry as it arrived. So the pitches are measured
 * out of the generated samples, per note, rather than taken on trust from the
 * constants.</p>
 *
 * <p>Measured by <b>zero crossings inside the first half of each note</b>, which
 * is an exact pitch reading here rather than an approximation: the fundamental
 * carries 0.82 of the amplitude against the second harmonic's 0.18, and
 * {@code 0.82 sin p + 0.18 sin 2p} factorises to {@code sin p (0.82 + 0.36 cos p)}
 * whose second factor is never negative — so the sum crosses zero exactly twice
 * per cycle of the fundamental and nowhere else. The first half rather than the
 * whole note because the envelope decays, and quantising a nearly silent tail to
 * 16-bit integers loses crossings.</p>
 */
@DisplayName("PowerChimeSound")
final class PowerChimeSoundTest
{
    /** Zero crossings per cycle of a tone — two, one each way. */
    private static final int CROSSINGS_PER_CYCLE = 2;

    /** How far a measured pitch may sit from the intended one. */
    private static final double PITCH_TOLERANCE_PERCENT = 8.0;

    @Nested
    @DisplayName("the direction, which is the whole message")
    final class Direction
    {
        @Test
        @DisplayName("the award RISES: low note then high")
        void readyRises()
        {
            final short[] pcm = PowerChimeSound.readySamples();

            assertThat(pitchOfNote(pcm, 0))
                .isCloseTo(PowerChimeSound.LOW_HZ, withinPercentage(PITCH_TOLERANCE_PERCENT));

            assertThat(pitchOfNote(pcm, 1))
                .isCloseTo(PowerChimeSound.HIGH_HZ, withinPercentage(PITCH_TOLERANCE_PERCENT));

            assertThat(pitchOfNote(pcm, 1)).isGreaterThan(pitchOfNote(pcm, 0));
        }

        @Test
        @DisplayName("the expiry FALLS: the same two notes backwards")
        void spentFalls()
        {
            final short[] pcm = PowerChimeSound.spentSamples();

            assertThat(pitchOfNote(pcm, 0))
                .isCloseTo(PowerChimeSound.HIGH_HZ, withinPercentage(PITCH_TOLERANCE_PERCENT));

            assertThat(pitchOfNote(pcm, 1))
                .isCloseTo(PowerChimeSound.LOW_HZ, withinPercentage(PITCH_TOLERANCE_PERCENT));

            assertThat(pitchOfNote(pcm, 1)).isLessThan(pitchOfNote(pcm, 0));
        }

        @Test
        @DisplayName("the two are different bytes, not one sound reached by two names")
        void theTwoVariantsDiffer()
        {
            // SoundBank maps two SoundIds here, and SoundBankTest's central
            // assertion is that no two ids share a buffer. This is that property at
            // its source.
            assertThat(PowerChimeSound.readySamples())
                .isNotEqualTo(PowerChimeSound.spentSamples());
        }

        @Test
        @DisplayName("the interval is a perfect fifth — related notes, not arbitrary ones")
        void theIntervalIsAFifth()
        {
            assertThat(PowerChimeSound.FIFTH_RATIO).isEqualTo(1.5);

            assertThat(PowerChimeSound.HIGH_HZ / PowerChimeSound.LOW_HZ).isEqualTo(1.5);
        }
    }

    @Nested
    @DisplayName("as a waveform")
    final class Waveform
    {
        @Test
        @DisplayName("silent at both ends AND at the seam between the notes")
        void everyBoundaryIsSilent()
        {
            // The seam is the assertion that matters and the one the endpoint ramps
            // do not cover. The pitch JUMPS in the middle of this sound, and a jump
            // taken at nonzero amplitude is a step discontinuity — the same click
            // the ramps exist to prevent, in the one place it is most audible.
            final short[] pcm = PowerChimeSound.readySamples();

            final int seam = PowerChimeSound.noteSampleCount();

            assertThat(pcm[0]).isZero();

            assertThat(pcm[pcm.length - 1]).isZero();

            assertThat(pcm[seam - 1]).as("the first note ends mid-cycle").isZero();

            assertThat(pcm[seam]).as("the second note starts mid-cycle").isZero();
        }

        @Test
        @DisplayName("both variants are exactly two whole notes long")
        void isTwoWholeNotes()
        {
            // One length for two ids, which is what lets SoundBank answer
            // sampleCount() for either of them with one number. Derived from the
            // per-note count rather than from DURATION_MS, so there is no rounding
            // remainder of silence on the end.
            assertThat(PowerChimeSound.NOTE_COUNT).isEqualTo(2);

            assertThat(PowerChimeSound.sampleCount())
                .isEqualTo(PowerChimeSound.noteSampleCount() * PowerChimeSound.NOTE_COUNT);

            assertThat(PowerChimeSound.readySamples())
                .hasSize(PowerChimeSound.sampleCount());

            assertThat(PowerChimeSound.spentSamples())
                .hasSize(PowerChimeSound.sampleCount());
        }

        @Test
        @DisplayName("neither variant clips, and neither is silence")
        void staysInsideFullScale()
        {
            assertThat(loudestOf(PowerChimeSound.readySamples()))
                .isLessThan(Short.MAX_VALUE)
                .isGreaterThan(Short.MAX_VALUE / 8);

            assertThat(loudestOf(PowerChimeSound.spentSamples()))
                .isLessThan(Short.MAX_VALUE)
                .isGreaterThan(Short.MAX_VALUE / 16);
        }

        @Test
        @DisplayName("the expiry is the quieter of the two, and measurably so")
        void spentIsQuieterThanReady()
        {
            // Missing the award means not knowing you have a better gun. Missing the
            // expiry means at worst being told something the on-screen countdown has
            // already spent four seconds telling you. The louder sound is the one
            // carrying information the player does not otherwise have.
            assertThat(PowerChimeSound.SPENT_PEAK).isLessThan(PowerChimeSound.READY_PEAK);

            assertThat(loudestOf(PowerChimeSound.spentSamples()))
                .isLessThan(loudestOf(PowerChimeSound.readySamples()));
        }

        @Test
        @DisplayName("the same bytes every time — the bake is reproducible")
        void isDeterministic()
        {
            assertThat(PowerChimeSound.readySamples())
                .isEqualTo(PowerChimeSound.readySamples());

            assertThat(PowerChimeSound.spentSamples())
                .isEqualTo(PowerChimeSound.spentSamples());
        }
    }

    @Nested
    @DisplayName("against the weapons it plays over")
    final class Distinguishable
    {
        @Test
        @DisplayName("STEPS rather than glides — the class of sound is the difference")
        void doesNotGlide()
        {
            // Every weapon in this engine begins with a bang and gets quieter, and
            // two of them glide. This holds a pitch, steps once, and holds again:
            // measured inside one note, the pitch at the start and at the middle are
            // the same figure, where the blaster's has fallen by a fifth of an
            // octave over the same span.
            final short[] chime = PowerChimeSound.readySamples();

            final int perNote = PowerChimeSound.noteSampleCount();

            assertThat(pitchOver(chime, 0, perNote / 4))
                .as("the note glides, so this is a sweep rather than a chime")
                .isCloseTo(pitchOver(chime, perNote / 4, perNote / 2),
                    withinPercentage(PITCH_TOLERANCE_PERCENT));
        }

        @Test
        @DisplayName("is over well inside the reward it announces")
        void isShorterThanTheRewardItAnnounces()
        {
            // A confirmation that outlasts the thing it is confirming is an
            // interruption. Four seconds of reward, 180 ms of chime.
            final int rewardMillis = 240 * 1000 / 60;

            assertThat(PowerChimeSound.DURATION_MS).isLessThan(rewardMillis / 4);
        }

        @Test
        @DisplayName("shares the sample rate, so one mixer serves every sound")
        void sharesTheSampleRate()
        {
            assertThat(PowerChimeSound.sampleRate()).isEqualTo(BlasterSound.sampleRate());
        }

        @Test
        @DisplayName("sits inside the weapons' register rather than outside it")
        void belongsToTheSameFamily()
        {
            // A chime pitched outside the weapon's register entirely would sound like
            // a different game's user interface pasted over this one. It belongs to
            // the same instrument family and is told apart by SHAPE.
            assertThat(PowerChimeSound.LOW_HZ).isGreaterThan(BlasterSound.END_HZ);

            assertThat(PowerChimeSound.HIGH_HZ).isLessThan(BlasterSound.START_HZ * 1.5);
        }
    }

    // The measured pitch of one note, in Hz, from its first half. See the class
    // Javadoc on why crossings give an exact reading for this waveform.
    private static double pitchOfNote(final short[] pcm, final int note)
    {
        final int perNote = PowerChimeSound.noteSampleCount();

        final int from = note * perNote;

        return pitchOver(pcm, from, from + perNote / 2);
    }

    // The measured pitch over an arbitrary window, in Hz.
    private static double pitchOver(final short[] pcm, final int from, final int to)
    {
        // MUTABLE local — crossings counted so far.
        int crossings = 0;

        for (int index = from + 1; index < to; index++)
        {
            if (pcm[index - 1] < 0 && pcm[index] >= 0 || pcm[index - 1] >= 0 && pcm[index] < 0)
            {
                crossings++;
            }
        }

        final double seconds = (to - from) / (double) PowerChimeSound.sampleRate();

        return crossings / seconds / CROSSINGS_PER_CYCLE;
    }

    // The loudest absolute sample in a buffer.
    private static int loudestOf(final short[] pcm)
    {
        // MUTABLE local — the loudest sample seen.
        int loudest = 0;

        for (final short sample : pcm)
        {
            loudest = Math.max(loudest, Math.abs(sample));
        }

        return loudest;
    }
}
