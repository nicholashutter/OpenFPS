/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the RIFF/WAVE container.
 *
 * <p>Header bugs are the worst kind of audio bug to find by ear, because the
 * usual symptom is that the file plays perfectly in the tool you check it with
 * and not at all in the one that matters. These read the bytes back with an
 * independent {@link ByteBuffer} rather than with the writer's own helpers, so a
 * consistent misunderstanding of the format cannot pass.</p>
 */
@DisplayName("WavAudio")
final class WavAudioTest
{
    /** A short recognisable payload — value equals index, so offsets are visible. */
    private static short[] ramp(final int count)
    {
        final short[] pcm = new short[count];

        for (int index = 0; index < count; index++)
        {
            pcm[index] = (short) index;
        }

        return pcm;
    }

    /** Reads the file back little-endian, the way a player would. */
    private static ByteBuffer reader(final byte[] wav)
    {
        return ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Reads a four-character chunk tag at an absolute offset. */
    private static String tagAt(final byte[] wav, final int offset)
    {
        return new String(wav, offset, 4, StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("the container")
    final class Container
    {
        @Test
        @DisplayName("is a 44-byte header followed by two bytes per sample")
        void shouldHaveTheCanonicalLength()
        {
            assertThat(WavAudio.wav(ramp(100), 22050))
                .hasSize(WavAudio.HEADER_BYTES + 200);
        }

        @Test
        @DisplayName("carries the four RIFF/WAVE tags in the right places")
        void shouldCarryTheChunkTags()
        {
            final byte[] wav = WavAudio.wav(ramp(4), 22050);

            assertThat(tagAt(wav, 0)).isEqualTo("RIFF");

            assertThat(tagAt(wav, 8)).isEqualTo("WAVE");

            assertThat(tagAt(wav, 12)).isEqualTo("fmt ");

            assertThat(tagAt(wav, 36)).isEqualTo("data");
        }

        @Test
        @DisplayName("sizes the RIFF chunk as everything after the first eight bytes")
        void shouldSizeTheRiffChunkCorrectly()
        {
            // The field half the WAV writers ever published get wrong. It is
            // neither the total length nor the payload length: it is the total
            // minus the "RIFF" tag and the size field itself. Wrong here means a
            // file that most players open happily and some refuse outright,
            // which is the worst possible failure mode.
            final byte[] wav = WavAudio.wav(ramp(64), 22050);

            assertThat(reader(wav).getInt(4)).isEqualTo(wav.length - 8);
        }

        @Test
        @DisplayName("sizes the data chunk as the samples alone")
        void shouldSizeTheDataChunkCorrectly()
        {
            final byte[] wav = WavAudio.wav(ramp(64), 22050);

            assertThat(reader(wav).getInt(40)).isEqualTo(128);

            assertThat(reader(wav).getInt(40)).isEqualTo(wav.length - WavAudio.HEADER_BYTES);
        }
    }

    @Nested
    @DisplayName("the format chunk")
    final class Format
    {
        @Test
        @DisplayName("declares uncompressed 16-bit mono PCM")
        void shouldDeclarePcm()
        {
            final ByteBuffer wav = reader(WavAudio.wav(ramp(8), 22050));

            assertThat(wav.getInt(16)).as("fmt chunk body size").isEqualTo(16);

            assertThat(wav.getShort(20)).as("format tag: 1 is PCM").isEqualTo((short) 1);

            assertThat(wav.getShort(22)).as("channels").isEqualTo((short) WavAudio.CHANNELS);

            assertThat(wav.getShort(34)).as("bits per sample")
                .isEqualTo((short) WavAudio.BITS_PER_SAMPLE);
        }

        @Test
        @DisplayName("stores the sample rate it was given")
        void shouldStoreTheSampleRate()
        {
            assertThat(reader(WavAudio.wav(ramp(8), 44100)).getInt(24)).isEqualTo(44100);

            assertThat(reader(WavAudio.wav(ramp(8), 22050)).getInt(24)).isEqualTo(22050);
        }

        @Test
        @DisplayName("derives byte rate and block align consistently with the rest")
        void shouldDeriveTheRedundantFields()
        {
            // Both are computable from the other fields, and both are stored
            // anyway because the format stores them. A player that trusts these
            // over the others must still be told the truth — disagreement here
            // is heard as the file playing at the wrong speed.
            final ByteBuffer wav = reader(WavAudio.wav(ramp(8), 22050));

            assertThat(wav.getInt(28)).as("byte rate").isEqualTo(22050 * 2);

            assertThat(wav.getShort(32)).as("block align").isEqualTo((short) 2);
        }
    }

    @Nested
    @DisplayName("the samples")
    final class Samples
    {
        @Test
        @DisplayName("survive the round trip in order and little-endian")
        void shouldRoundTripTheSamples()
        {
            final short[] pcm = ramp(32);

            final ByteBuffer wav = reader(WavAudio.wav(pcm, 22050));

            for (int index = 0; index < pcm.length; index++)
            {
                assertThat(wav.getShort(WavAudio.HEADER_BYTES + index * 2))
                    .isEqualTo(pcm[index]);
            }
        }

        @Test
        @DisplayName("keep negative values intact")
        void shouldRoundTripNegativeSamples()
        {
            // Half of any waveform is negative, and a sign-extension mistake in
            // the byte packing is inaudible on a ramp of small positives and
            // catastrophic on a real signal.
            final short[] pcm = {Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE};

            final ByteBuffer wav = reader(WavAudio.wav(pcm, 22050));

            for (int index = 0; index < pcm.length; index++)
            {
                assertThat(wav.getShort(WavAudio.HEADER_BYTES + index * 2))
                    .isEqualTo(pcm[index]);
            }
        }

        @Test
        @DisplayName("an empty buffer still produces a valid header-only file")
        void shouldAcceptAnEmptyBuffer()
        {
            final byte[] wav = WavAudio.wav(new short[0], 22050);

            assertThat(wav).hasSize(WavAudio.HEADER_BYTES);

            assertThat(reader(wav).getInt(40)).isEqualTo(0);

            assertThat(reader(wav).getInt(4)).isEqualTo(WavAudio.HEADER_BYTES - 8);
        }
    }

    @Nested
    @DisplayName("rejected input")
    final class Rejected
    {
        @Test
        @DisplayName("refuses a null buffer and a non-positive rate")
        void shouldRejectProgrammingErrors()
        {
            // The one place in the audio subsystem that throws, and deliberately
            // so: these are caller bugs, not runtime conditions. "The speakers
            // are missing" degrades; "you passed null" does not.
            assertThatThrownBy(() -> WavAudio.wav(null, 22050))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

            assertThatThrownBy(() -> WavAudio.wav(ramp(4), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

            assertThatThrownBy(() -> WavAudio.wav(ramp(4), -22050))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the generated blaster")
    final class GeneratedBlaster
    {
        @Test
        @DisplayName("wraps into a file the adapters can hand to libGDX")
        void shouldWrapTheBlaster()
        {
            // The end-to-end shape of what GdxAudioPort stages, minus the file
            // write it cannot do here. Roughly 8 KB, which is why compression
            // was never worth a third-party decoder.
            final byte[] wav =
                WavAudio.wav(BlasterSound.samples(), BlasterSound.sampleRate());

            assertThat(wav)
                .hasSize(WavAudio.HEADER_BYTES + BlasterSound.sampleCount() * 2);

            assertThat(tagAt(wav, 0)).isEqualTo("RIFF");

            assertThat(reader(wav).getInt(24)).isEqualTo(BlasterSound.SAMPLE_RATE);

            assertThat(wav.length).isLessThan(16 * 1024);
        }
    }
}
