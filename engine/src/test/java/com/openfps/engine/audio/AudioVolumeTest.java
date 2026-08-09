/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the one definition of what a volume may be.
 *
 * <p>Four lines of production code with more test than implementation, which is
 * the right ratio here: every {@code I_AudioPort} depends on this agreeing with
 * itself across platforms, and the interesting case — NaN — is the one the
 * obvious implementation gets silently wrong.</p>
 */
@DisplayName("AudioVolume")
final class AudioVolumeTest
{
    @Nested
    @DisplayName("inside the range")
    final class InRange
    {
        @Test
        @DisplayName("passes an ordinary volume through untouched")
        void shouldPassThroughAValidVolume()
        {
            assertThat(AudioVolume.clamp(0.5f)).isEqualTo(0.5f);

            assertThat(AudioVolume.clamp(0.001f)).isEqualTo(0.001f);

            assertThat(AudioVolume.clamp(0.999f)).isEqualTo(0.999f);
        }

        @Test
        @DisplayName("keeps both endpoints exactly")
        void shouldKeepTheEndpoints()
        {
            // Exactly, not approximately: silence that is 1e-7 rather than 0
            // still drives a voice on some mixers, and full scale that is
            // 1 + 1e-7 clips on others.
            assertThat(AudioVolume.clamp(AudioVolume.SILENT)).isEqualTo(0.0f);

            assertThat(AudioVolume.clamp(AudioVolume.FULL)).isEqualTo(1.0f);
        }
    }

    @Nested
    @DisplayName("outside the range")
    final class OutOfRange
    {
        @Test
        @DisplayName("clamps a negative volume to silence")
        void shouldClampBelow()
        {
            assertThat(AudioVolume.clamp(-0.5f)).isEqualTo(AudioVolume.SILENT);

            assertThat(AudioVolume.clamp(-1000.0f)).isEqualTo(AudioVolume.SILENT);
        }

        @Test
        @DisplayName("clamps an overdriven volume to full scale")
        void shouldClampAbove()
        {
            assertThat(AudioVolume.clamp(1.5f)).isEqualTo(AudioVolume.FULL);

            assertThat(AudioVolume.clamp(1000.0f)).isEqualTo(AudioVolume.FULL);
        }

        @Test
        @DisplayName("clamps both infinities")
        void shouldClampInfinities()
        {
            assertThat(AudioVolume.clamp(Float.POSITIVE_INFINITY))
                .isEqualTo(AudioVolume.FULL);

            assertThat(AudioVolume.clamp(Float.NEGATIVE_INFINITY))
                .isEqualTo(AudioVolume.SILENT);
        }
    }

    @Nested
    @DisplayName("NaN")
    final class NotANumber
    {
        @Test
        @DisplayName("becomes silence rather than propagating")
        void shouldMapNanToSilence()
        {
            // The whole reason this class exists. Math.max(0, Math.min(1, NaN))
            // is NaN — every comparison against NaN is false, so both clamps
            // pass it straight through — and a NaN gain silences the source on
            // OpenAL while playing at full scale on some SoundPool builds. The
            // same "clamped" value is then inaudible on desktop and deafening
            // on a phone.
            final float clamped = AudioVolume.clamp(Float.NaN);

            assertThat(Float.isNaN(clamped)).as("NaN reached the mixer").isFalse();

            assertThat(clamped).isEqualTo(AudioVolume.SILENT);
        }

        @Test
        @DisplayName("is produced by the obvious one-liner, which is why this is not it")
        void shouldDocumentWhyTheOneLinerIsWrong()
        {
            // Not a test of production code — a test of the claim in the
            // Javadoc, so that anyone tempted to "simplify" clamp() back to one
            // line finds out here rather than on a phone.
            final float naive = Math.max(0.0f, Math.min(1.0f, Float.NaN));

            assertThat(Float.isNaN(naive))
                .as("if this ever fails, the one-liner became safe and the "
                    + "explanation in AudioVolume needs revisiting")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("negative zero")
    final class NegativeZero
    {
        @Test
        @DisplayName("comes back as ordinary silence")
        void shouldNormaliseNegativeZero()
        {
            // -0.0f < 0.0f is false, so it falls through the range checks
            // untouched. It is silence either way; the assertion pins the
            // behaviour so a future rewrite cannot start returning -0.0 to a
            // backend that compares volumes for equality.
            assertThat(AudioVolume.clamp(-0.0f)).isEqualTo(0.0f);
        }
    }
}
