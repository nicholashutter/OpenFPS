/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import com.openfps.engine.audio.port.SoundId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the catalogue that says what each {@link SoundId} sounds like.
 *
 * <p><b>This is the regression for the bug the second sound found.</b> Before this
 * class existed the adapter reached into {@link BlasterSound} for every id it was
 * handed, so {@code BOT_WEAPON_FIRE} would have staged, loaded, warmed and played
 * — as the player's own weapon. The failure had no symptom except the sound
 * itself, which is the one thing CI cannot check, so what is asserted here is the
 * property that made it possible: <b>every id must map to different bytes</b>.</p>
 */
@DisplayName("SoundBank")
final class SoundBankTest
{
    @Test
    @DisplayName("every sound is a DIFFERENT sound — the bug this class exists to prevent")
    void noTwoSoundsShareABuffer()
    {
        // Written over values() rather than over a list of the two that exist, so a
        // third sound is covered by existing rather than by somebody remembering to
        // add it here. That is exactly the discipline the adapter's loop over
        // values() already had, and which made the shared-buffer bug invisible.
        final Set<String> seen = new HashSet<>();

        for (final SoundId sound : SoundId.values())
        {
            final byte[] wav = SoundBank.wav(sound);

            assertThat(wav).as("%s baked nothing", sound).isNotEmpty();

            assertThat(seen.add(fingerprint(wav)))
                .as("%s is byte-identical to another sound", sound)
                .isTrue();
        }

        assertThat(seen).hasSameSizeAs(SoundId.values());
    }

    @Test
    @DisplayName("every sound gets its own staged file name")
    void noTwoSoundsShareAFileName()
    {
        // They share a temp directory, so two ids on one name would have one
        // overwrite the other — and which one won would depend on bake order.
        final Set<String> names = new HashSet<>();

        for (final SoundId sound : SoundId.values())
        {
            final String name = SoundBank.fileName(sound);

            assertThat(name).endsWith(".wav");

            assertThat(name).doesNotContain("/").doesNotContain("\\");

            assertThat(names.add(name)).as("%s reuses a file name", sound).isTrue();
        }
    }

    @Test
    @DisplayName("reports each sound's own rate and length, not the first one's")
    void reportsPerSoundFigures()
    {
        // The log line an adapter writes is the only evidence a user has that audio
        // came up — see I_AudioPort.isAudible on why silent success and silent
        // failure must not look alike — and it was reporting the blaster's length
        // for every id.
        assertThat(SoundBank.sampleCount(SoundId.WEAPON_FIRE))
            .isEqualTo(BlasterSound.sampleCount());

        assertThat(SoundBank.sampleCount(SoundId.BOT_WEAPON_FIRE))
            .isEqualTo(CarbineSound.sampleCount());

        assertThat(SoundBank.sampleCount(SoundId.BOT_WEAPON_FIRE))
            .isNotEqualTo(SoundBank.sampleCount(SoundId.WEAPON_FIRE));

        assertThat(SoundBank.sampleRate(SoundId.BOT_WEAPON_FIRE))
            .isEqualTo(CarbineSound.sampleRate());
    }

    @Test
    @DisplayName("every bake is a complete WAVE file the size its header claims")
    void bakesWellFormedFiles()
    {
        for (final SoundId sound : SoundId.values())
        {
            final byte[] wav = SoundBank.wav(sound);

            final int expected = WavAudio.HEADER_BYTES + SoundBank.sampleCount(sound) * 2;

            assertThat(wav).as("%s", sound).hasSize(expected);

            assertThat(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        }
    }

    @Test
    @DisplayName("a null id is answered rather than rejected")
    void nullIsTolerated()
    {
        // I_AudioPort.play promises that an unknown sound is ignored rather than
        // rejected, and an adapter has to be able to keep that promise without a
        // null check of its own.
        assertThat(SoundBank.wav(null)).isEqualTo(SoundBank.wav(SoundId.WEAPON_FIRE));

        assertThat(SoundBank.fileName(null))
            .isEqualTo(SoundBank.fileName(SoundId.WEAPON_FIRE));
    }

    // A cheap content fingerprint — the length and every byte folded together. Not
    // a hash for security, just enough that two different buffers cannot collide by
    // accident at these sizes.
    private static String fingerprint(final byte[] bytes)
    {
        // MUTABLE local — the running fold.
        long fold = bytes.length;

        for (final byte value : bytes)
        {
            fold = fold * 31 + value;
        }

        return bytes.length + ":" + fold;
    }
}
