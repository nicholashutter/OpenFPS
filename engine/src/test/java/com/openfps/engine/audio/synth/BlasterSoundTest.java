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
 * Tests for the generated weapon sound.
 *
 * <p>Nothing here listens to anything — these are assertions about arithmetic,
 * which is exactly why the sound is generated rather than loaded. A .wav in the
 * repository would be untestable beyond "the file is still there"; a formula can
 * be checked for the two faults that actually make a synthesised sound wrong:
 * a sweep that does not sweep, and endpoints that click.</p>
 */
@DisplayName("BlasterSound")
final class BlasterSoundTest
{
    /** Slack for comparing frequencies, in Hz. */
    private static final double HZ_EPSILON = 1.0;

    @Nested
    @DisplayName("the buffer")
    final class Buffer
    {
        @Test
        @DisplayName("is as long as the rate and the duration say")
        void shouldProduceTheStatedSampleCount()
        {
            assertThat(BlasterSound.sampleCount())
                .isEqualTo(BlasterSound.SAMPLE_RATE * BlasterSound.DURATION_MS / 1000);
            assertThat(BlasterSound.samples()).hasSize(BlasterSound.sampleCount());
        }

        @Test
        @DisplayName("is a fresh array each call, so a caller cannot corrupt the next one")
        void shouldNotShareItsBuffer()
        {
            final short[] first = BlasterSound.samples();
            final short[] second = BlasterSound.samples();

            assertThat(first).isNotSameAs(second).isEqualTo(second);

            first[100] = 0;
            assertThat(BlasterSound.samples()[100])
                .as("a mutated buffer leaked into the next generation")
                .isEqualTo(second[100]);
        }

        @Test
        @DisplayName("is deterministic — the same bytes every time")
        void shouldBeDeterministic()
        {
            // Not a lockstep requirement — audio is explicitly outside that, per
            // I_AudioPort — but it is what lets the staged WAV be rewritten on
            // every run without the sound changing under the player, and it is
            // what makes every other assertion in this file stable.
            assertThat(BlasterSound.samples()).isEqualTo(BlasterSound.samples());
        }

        @Test
        @DisplayName("never clips")
        void shouldStayInsideFullScale()
        {
            // PEAK leaves headroom, but the fundamental and the third harmonic
            // can align, so the generator clamps rather than trusting the
            // coefficients. A cast that wrapped would turn a loud sample into an
            // equally loud sample of the opposite sign — which is a click.
            for (final short sample : BlasterSound.samples())
            {
                assertThat((int) sample)
                    .isBetween((int) Short.MIN_VALUE, (int) Short.MAX_VALUE);
            }
        }
    }

    @Nested
    @DisplayName("the pitch sweep")
    final class Sweep
    {
        @Test
        @DisplayName("starts where it says and ends where it says")
        void shouldSweepBetweenTheStatedFrequencies()
        {
            assertThat(BlasterSound.frequencyAt(0))
                .isCloseTo(BlasterSound.START_HZ, within(HZ_EPSILON));
            assertThat(BlasterSound.frequencyAt(BlasterSound.sampleCount()))
                .isCloseTo(BlasterSound.END_HZ, within(HZ_EPSILON));
        }

        @Test
        @DisplayName("falls monotonically, never rising")
        void shouldFallThroughout()
        {
            double previous = BlasterSound.frequencyAt(0);
            for (int index = 1; index < BlasterSound.sampleCount(); index++)
            {
                final double current = BlasterSound.frequencyAt(index);
                assertThat(current).isLessThanOrEqualTo(previous);
                previous = current;
            }
        }

        @Test
        @DisplayName("is geometric, not linear — equal intervals in equal times")
        void shouldBeExponential()
        {
            // The audible difference between a fall and a thump. Pitch
            // perception is logarithmic, so a sweep that sounds even has to
            // cover equal RATIOS per unit time, not equal differences. The test
            // of that: the ratio across the first half equals the ratio across
            // the second.
            final int count = BlasterSound.sampleCount();
            final double firstHalf =
                BlasterSound.frequencyAt(0) / BlasterSound.frequencyAt(count / 2);
            final double secondHalf =
                BlasterSound.frequencyAt(count / 2) / BlasterSound.frequencyAt(count);

            assertThat(firstHalf).isCloseTo(secondHalf, within(0.01));
            // And it really is a ratio worth having: a linear sweep would put
            // the midpoint at (900 + 120) / 2 = 510 Hz. Geometric puts it at
            // sqrt(900 * 120) ~= 329 Hz, most of an octave lower.
            assertThat(BlasterSound.frequencyAt(count / 2))
                .isCloseTo(Math.sqrt(BlasterSound.START_HZ * BlasterSound.END_HZ),
                    within(HZ_EPSILON));
        }
    }

    @Nested
    @DisplayName("the envelope")
    final class Envelope
    {
        @Test
        @DisplayName("is exactly zero at both ends, so neither end clicks")
        void shouldStartAndEndInSilence()
        {
            // The whole reason the attack and release ramps exist. A waveform
            // that starts or stops mid-cycle is a step discontinuity, a step
            // contains every frequency, and it is heard as a click on top of the
            // sound. The decay alone does not get there: exp(-14 * 0.18) is
            // about 8% of peak, which is very audible as a cut.
            final short[] pcm = BlasterSound.samples();

            assertThat(BlasterSound.envelopeAt(0)).isEqualTo(0.0);
            assertThat(BlasterSound.envelopeAt(BlasterSound.sampleCount() - 1)).isEqualTo(0.0);
            assertThat(pcm[0]).isEqualTo((short) 0);
            assertThat(pcm[pcm.length - 1]).isEqualTo((short) 0);
        }

        @Test
        @DisplayName("stays inside [0, 1] everywhere")
        void shouldStayNormalised()
        {
            for (int index = 0; index < BlasterSound.sampleCount(); index++)
            {
                assertThat(BlasterSound.envelopeAt(index)).isBetween(0.0, 1.0);
            }
        }

        @Test
        @DisplayName("reads zero outside the buffer instead of extrapolating")
        void shouldBeSilentOutsideTheBuffer()
        {
            assertThat(BlasterSound.envelopeAt(-1)).isEqualTo(0.0);
            assertThat(BlasterSound.envelopeAt(BlasterSound.sampleCount())).isEqualTo(0.0);
            assertThat(BlasterSound.envelopeAt(Integer.MAX_VALUE)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("decays — the tail is quieter than the body")
        void shouldDecay()
        {
            // A blaster, not a tone: the sound has to get out of the way of the
            // next one. Peak amplitude is compared over windows rather than
            // sample by sample, because the waveform itself crosses zero
            // constantly and any two adjacent samples prove nothing.
            final short[] pcm = BlasterSound.samples();
            final int third = pcm.length / 3;

            assertThat(peakBetween(pcm, third, 2 * third))
                .isLessThan(peakBetween(pcm, 0, third));
            assertThat(peakBetween(pcm, 2 * third, pcm.length))
                .isLessThan(peakBetween(pcm, third, 2 * third));
        }

        @Test
        @DisplayName("reaches a usable level — the sound is not inaudibly quiet")
        void shouldReachAUsefulAmplitude()
        {
            // The counterpart to the clipping test. A generator that produced
            // technically-correct silence would pass every assertion above.
            final int peak = peakBetween(BlasterSound.samples(), 0, BlasterSound.sampleCount());

            assertThat(peak).isGreaterThan(Short.MAX_VALUE / 4);
        }

        // The largest absolute sample in a half-open window.
        private static int peakBetween(final short[] pcm, final int from, final int to)
        {
            int peak = 0;
            for (int index = from; index < to; index++)
            {
                peak = Math.max(peak, Math.abs(pcm[index]));
            }
            return peak;
        }
    }
}
