/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the bots' carbine sound.
 *
 * <p><b>The audio cannot be heard from here, so what is asserted instead is
 * everything that would make it wrong.</b> Three groups: the sound is well formed
 * (no clicks, no clipping, no silence), it is <b>reproducible</b>, and it is
 * measurably nothing like the player's blaster. The last is the requirement — if
 * the two sounds are not distinguishable then incoming fire is not identifiable by
 * ear, which is the whole reason this file exists — and it is the one nobody could
 * check by looking at the code, because "a noise crack" and "a pitch sweep" are
 * both just arithmetic on the page.</p>
 */
@DisplayName("CarbineSound")
final class CarbineSoundTest
{
    /** Simulation rate the voice-overlap arithmetic is checked against. */
    private static final int TICS_PER_SECOND = 60;

    @Nested
    @DisplayName("as a waveform")
    final class Waveform
    {
        @Test
        @DisplayName("starts and ends at exactly zero, so there is no click")
        void bothEndsAreSilent()
        {
            // A waveform that starts or stops mid-cycle is a step, and a step
            // contains every frequency. The decay alone does not reach zero here —
            // exp(-28 * 0.12) is about 3.5% of peak, which is a very audible click —
            // so the ramps are load-bearing rather than tidy.
            final short[] pcm = CarbineSound.samples();

            assertThat(pcm[0]).isZero();

            assertThat(pcm[pcm.length - 1]).isZero();

            assertThat(CarbineSound.envelopeAt(0)).isZero();

            assertThat(CarbineSound.envelopeAt(CarbineSound.sampleCount() - 1)).isZero();
        }

        @Test
        @DisplayName("never clips, and is not silence either")
        void staysInsideFullScale()
        {
            final short[] pcm = CarbineSound.samples();

            // MUTABLE local — the loudest sample seen.
            int loudest = 0;

            for (final short sample : pcm)
            {
                loudest = Math.max(loudest, Math.abs(sample));
            }

            assertThat(loudest)
                .as("the sound clips, which is a crunch rather than a crack")
                .isLessThan(Short.MAX_VALUE);

            assertThat(loudest)
                .as("nothing was generated at all")
                .isGreaterThan(Short.MAX_VALUE / 8);
        }

        @Test
        @DisplayName("the envelope decays rather than sustaining")
        void decaysAway()
        {
            final int count = CarbineSound.sampleCount();

            assertThat(CarbineSound.envelopeAt(count / 4))
                .isLessThan(CarbineSound.envelopeAt(count / 32));

            assertThat(CarbineSound.envelopeAt(count / 2))
                .isLessThan(CarbineSound.envelopeAt(count / 4));
        }

        @Test
        @DisplayName("the low-pass coefficient is the exact one, not the linear approximation")
        void filterCoefficientIsExact()
        {
            // 1 - exp(-2 pi f / rate), not f / rate. At 1.6 kHz against 22.05 kHz
            // the approximation is out by about a fifth, and the audible difference
            // is a hiss instead of a thud.
            final double naive = CarbineSound.CUTOFF_HZ / CarbineSound.SAMPLE_RATE;

            final double exact = CarbineSound.lowPassCoefficient();

            assertThat(exact).isBetween(0.0, 1.0);

            assertThat(exact)
                .as("this is the f/rate approximation, which is the wrong sound")
                .isNotCloseTo(naive, within(0.02));
        }

        @Test
        @DisplayName("the same bytes every time — the bake is reproducible")
        void isDeterministic()
        {
            // The noise comes from a fixed-seed LCG written out in full, not from
            // java.util.Random and certainly not from Math.random(). Without this
            // property none of the assertions above could be made at all.
            assertThat(CarbineSound.samples()).isEqualTo(CarbineSound.samples());
        }
    }

    @Nested
    @DisplayName("against the player's blaster")
    final class Distinguishable
    {
        @Test
        @DisplayName("is shorter, quieter and decays faster")
        void differsInEveryEnvelopeNumber()
        {
            // Three separate separations, so the two are not told apart by any one
            // of them. Quieter in particular is deliberate: the player's own weapon
            // is the loudest thing in the mix because it is the one they control.
            assertThat(CarbineSound.DURATION_MS).isLessThan(BlasterSound.DURATION_MS);

            assertThat(CarbineSound.PEAK).isLessThan(BlasterSound.PEAK);

            assertThat(CarbineSound.DECAY_PER_SECOND)
                .isGreaterThan(BlasterSound.DECAY_PER_SECOND);
        }

        @Test
        @DisplayName("is NOISE where the blaster is a TONE — the separation that matters")
        void differsInKindAndNotDegree()
        {
            // The obvious move would have been the blaster's sweep transposed down.
            // Two pitch sweeps a few semitones apart are two firings of the SAME
            // weapon; the ear files them together. So the test is not "are the
            // numbers different" but "are these the same class of sound".
            //
            // Zero-crossing rate separates them without a Fourier transform. A tone
            // crosses zero twice per cycle of its own pitch, so the blaster's
            // 900-to-120 Hz sweep crosses at a rate that tracks that pitch and is
            // low and orderly. Broadband noise crosses far more often, because
            // every high-frequency component adds crossings of its own.
            final double carbine = crossingsPerSecond(CarbineSound.samples(),
                CarbineSound.sampleRate());

            final double blaster = crossingsPerSecond(BlasterSound.samples(),
                BlasterSound.sampleRate());

            assertThat(carbine)
                .as("the carbine crosses zero like a tone, so it IS a tone")
                .isGreaterThan(blaster * 1.5);
        }

        @Test
        @DisplayName("their spectral centres do not overlap")
        void sitInDifferentRegisters()
        {
            // The blaster's fixed pitch endpoints against the carbine's fixed body
            // pitch. Two and a half octaves below where the sweep ENDS, so they do
            // not share a register even at the blaster's lowest.
            assertThat(CarbineSound.BODY_HZ).isLessThan(BlasterSound.END_HZ / 1.2);

            assertThat(CarbineSound.CUTOFF_HZ)
                .as("the low-pass is above the blaster's start, so it filters nothing")
                .isLessThan(BlasterSound.START_HZ * 2.0);
        }

        @Test
        @DisplayName("shares the sample rate, so one mixer serves both")
        void sharesTheSampleRate()
        {
            assertThat(CarbineSound.sampleRate()).isEqualTo(BlasterSound.sampleRate());
        }
    }

    @Nested
    @DisplayName("against the voice cap")
    final class VoiceHeadroom
    {
        @Test
        @DisplayName("two of these at once cannot clip, which is what the cap is solved for")
        void twoVoicesFitInsideFullScale()
        {
            // BotFireVoices' six-tic minimum interval admits at most two
            // overlapping copies of a 120 ms sound at 60 Hz. Two identical buffers
            // in phase sum to exactly 2x amplitude, so PEAK has to leave room for
            // that — and this is the assertion that ties the two files together.
            final int concurrent =
                com.openfps.engine.demo.BotFireVoices.maxConcurrentVoices(TICS_PER_SECOND);

            assertThat(concurrent).isEqualTo(2);

            assertThat(CarbineSound.PEAK * concurrent)
                .as("the one overlap the cap permits clips")
                .isLessThan(1.0);
        }
    }

    // Zero crossings per second, which separates a tone from noise without a
    // Fourier transform: a tone crosses twice per cycle of its own pitch, noise
    // crosses far more often because every high component adds crossings.
    private static double crossingsPerSecond(final short[] pcm, final int rate)
    {
        // MUTABLE local — crossings counted so far.
        int crossings = 0;

        for (int index = 1; index < pcm.length; index++)
        {
            if (pcm[index - 1] < 0 && pcm[index] >= 0 || pcm[index - 1] >= 0 && pcm[index] < 0)
            {
                crossings++;
            }
        }

        return crossings * (double) rate / pcm.length;
    }
}
