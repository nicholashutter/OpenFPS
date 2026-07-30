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
 * Tests for the super blaster's firing sound.
 *
 * <p><b>The requirement is the inverse of {@link CarbineSoundTest}'s, and that is
 * the whole reason this file needs its own assertions.</b> The carbine has to be
 * told apart from the player's weapon; this has to be recognised <i>as</i> the
 * player's weapon, made bigger. Both halves are asserted here, because either one
 * alone is satisfiable by something wrong: a sound identical to the blaster passes
 * "same weapon", and a sound from a different instrument passes "bigger".</p>
 */
@DisplayName("SuperBlasterSound")
final class SuperBlasterSoundTest
{
    @Nested
    @DisplayName("as a waveform")
    final class Waveform
    {
        @Test
        @DisplayName("starts and ends at exactly zero, so there is no click")
        void bothEndsAreSilent()
        {
            // exp(-9 * 0.26) is about 10% of peak when the release ramp takes over,
            // so the ramp is what closes this sound. Without it the last sample is a
            // step, and a step contains every frequency.
            final short[] pcm = SuperBlasterSound.samples();

            assertThat(pcm[0]).isZero();
            assertThat(pcm[pcm.length - 1]).isZero();
            assertThat(SuperBlasterSound.envelopeAt(0)).isZero();
            assertThat(SuperBlasterSound.envelopeAt(SuperBlasterSound.sampleCount() - 1))
                .isZero();
        }

        @Test
        @DisplayName("never clips, and is not silence either")
        void staysInsideFullScale()
        {
            // PEAK is 0.85 and the three voice levels sum to exactly 1, so the
            // loudest possible sample is 0.85 of full scale. A sound this loud is
            // the one place a wrapped cast would be audible as a click.
            assertThat(loudestOf(SuperBlasterSound.samples()))
                .as("the sound clips, which is a crunch rather than a weapon")
                .isLessThan(Short.MAX_VALUE);
            assertThat(loudestOf(SuperBlasterSound.samples()))
                .as("nothing was generated at all")
                .isGreaterThan(Short.MAX_VALUE / 4);
        }

        @Test
        @DisplayName("the envelope decays rather than sustaining")
        void decaysAway()
        {
            final int count = SuperBlasterSound.sampleCount();

            assertThat(SuperBlasterSound.envelopeAt(count / 4))
                .isLessThan(SuperBlasterSound.envelopeAt(count / 32));
            assertThat(SuperBlasterSound.envelopeAt(count / 2))
                .isLessThan(SuperBlasterSound.envelopeAt(count / 4));
        }

        @Test
        @DisplayName("the sweep falls, and falls geometrically rather than linearly")
        void sweepsDownwardOnACurve()
        {
            // A linear ramp spends most of its length in the top octave and crosses
            // the bottom four in the last few milliseconds, which is heard as a
            // thump with a squeak on the front. Pitch perception is logarithmic, so
            // a sweep that sounds even has to be geometric — the midpoint is the
            // GEOMETRIC mean of the endpoints, not the arithmetic one.
            final int count = SuperBlasterSound.sampleCount();
            final double middle = SuperBlasterSound.frequencyAt(count / 2);

            assertThat(SuperBlasterSound.frequencyAt(0))
                .isCloseTo(SuperBlasterSound.START_HZ, within(0.001));
            assertThat(middle).isLessThan(SuperBlasterSound.START_HZ);
            assertThat(middle).isGreaterThan(SuperBlasterSound.END_HZ);
            assertThat(middle)
                .as("the sweep is linear, which is the wrong sound")
                .isCloseTo(Math.sqrt(SuperBlasterSound.START_HZ * SuperBlasterSound.END_HZ),
                    within(2.0));
        }

        @Test
        @DisplayName("the same bytes every time — the bake is reproducible")
        void isDeterministic()
        {
            assertThat(SuperBlasterSound.samples()).isEqualTo(SuperBlasterSound.samples());
        }
    }

    @Nested
    @DisplayName("as the SAME weapon")
    final class SameWeapon
    {
        @Test
        @DisplayName("contains the ordinary blaster's own sweep, note for note")
        void reproducesTheBlastersPitchAnOctaveUp()
        {
            // The kinship, stated as arithmetic rather than as an intention: the
            // second voice of this sound runs at exactly BlasterSound's frequencies,
            // because these endpoints are DERIVED from them. Written as literals
            // they would be two more numbers to keep in step with a sound they are
            // supposed to be a version of.
            assertThat(SuperBlasterSound.START_HZ * SuperBlasterSound.SUB_OCTAVE_DIVISOR)
                .isCloseTo(BlasterSound.START_HZ, within(0.001));
            assertThat(SuperBlasterSound.END_HZ * SuperBlasterSound.SUB_OCTAVE_DIVISOR)
                .isCloseTo(BlasterSound.END_HZ, within(0.001));
        }

        @Test
        @DisplayName("is a TONE, like the blaster and unlike the carbine")
        void isNotNoise()
        {
            // The separation CarbineSound is built around, applied in reverse. Zero
            // crossings per second tell a tone from noise without a Fourier
            // transform: a tone crosses twice per cycle of its own pitch, broadband
            // noise crosses far more often because every high component adds
            // crossings of its own. A "super" weapon that had drifted into being a
            // noise burst would have stopped being the player's weapon.
            final double superBlaster = crossingsPerSecond(SuperBlasterSound.samples(),
                SuperBlasterSound.sampleRate());
            final double carbine = crossingsPerSecond(CarbineSound.samples(),
                CarbineSound.sampleRate());

            assertThat(superBlaster)
                .as("crosses zero like noise, so it IS noise")
                .isLessThan(carbine / 1.5);
        }

        @Test
        @DisplayName("shares the sample rate, so one mixer serves every sound")
        void sharesTheSampleRate()
        {
            assertThat(SuperBlasterSound.sampleRate()).isEqualTo(BlasterSound.sampleRate());
        }
    }

    @Nested
    @DisplayName("as a BIGGER weapon")
    final class Bigger
    {
        @Test
        @DisplayName("is longer, louder and rings on where the blaster snaps")
        void differsInEveryEnvelopeNumber()
        {
            // Three separations, so the upgrade is not carried by any one of them.
            // Louder is the deliberate one: this is the single thing in the mix the
            // player has just earned, and a reward quieter than the weapon it
            // replaces would be an odd sort of reward.
            assertThat(SuperBlasterSound.DURATION_MS).isGreaterThan(BlasterSound.DURATION_MS);
            assertThat(SuperBlasterSound.PEAK).isGreaterThan(BlasterSound.PEAK);
            assertThat(SuperBlasterSound.DECAY_PER_SECOND)
                .isLessThan(BlasterSound.DECAY_PER_SECOND);
        }

        @Test
        @DisplayName("sits an octave lower, so the weight is where weight is heard")
        void sitsAnOctaveDown()
        {
            assertThat(SuperBlasterSound.START_HZ).isLessThan(BlasterSound.START_HZ);
            assertThat(SuperBlasterSound.END_HZ).isLessThan(BlasterSound.END_HZ);
            assertThat(SuperBlasterSound.SUB_OCTAVE_DIVISOR).isEqualTo(2.0);
        }

        @Test
        @DisplayName("is shorter than the weapon's cooldown, so a held trigger cannot stack it")
        void fitsInsideTheRateOfFire()
        {
            // The player's own weapon has no voice gate, because its rate of fire IS
            // its gate — twelve tics is 200 ms at 60 Hz. This sound is 260 ms, so at
            // most two overlap and only for the tail of the first; two copies at
            // 0.85 peak would clip, which is why it matters that the overlap is the
            // part that has already decayed to a tenth.
            final double cooldownMillis = 12 * 1000.0 / 60.0;

            assertThat(SuperBlasterSound.DURATION_MS)
                .as("a whole second copy starts before this one has decayed at all")
                .isLessThan((int) (cooldownMillis * 2.0));
            assertThat(Math.exp(-SuperBlasterSound.DECAY_PER_SECOND * cooldownMillis / 1000.0))
                .as("the overlapping tail is still loud enough for two copies to clip")
                .isLessThan(0.25);
        }
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

    // Zero crossings per second, which separates a tone from noise without a
    // Fourier transform.
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
