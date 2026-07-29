/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

/**
 * S_ Wraps raw PCM samples in a RIFF/WAVE container.
 *
 * <h2>Why a container at all, when we generated the samples ourselves</h2>
 *
 * <p>Because the audio backends will not take samples. libGDX's
 * {@code Audio.newSound} accepts a {@code FileHandle} and nothing else, and
 * Android's {@code SoundPool} underneath it wants a file descriptor. There is
 * no {@code newSound(byte[])} on either. So the shortest path from "we have
 * 3,969 shorts" to "the speakers make a noise" is a 44-byte header and a temp
 * file, and this class is the header.</p>
 *
 * <p>The alternative — {@code Audio.newAudioDevice}, which does take raw
 * samples — was rejected: {@code writeSamples} <b>blocks the calling thread
 * until the sound has finished playing</b>, and the caller here is the game
 * loop thread. A 180 ms block would cost eleven tics per shot.</p>
 *
 * <p>Uncompressed 16-bit PCM specifically, rather than OGG or MP3: the decoders
 * for those are third-party, {@code AGENTS.md} rules out adding runtime
 * dependencies casually, and the payload is 8 KB. Compression buys nothing and
 * costs a licence question.</p>
 *
 * <h2>The format, and the one field everybody gets wrong</h2>
 *
 * <p>RIFF is little-endian throughout, including on big-endian hosts — it is a
 * file format, not a memory layout, so the bytes are written explicitly rather
 * than by casting a buffer. The two size fields are the trap: the RIFF chunk
 * size counts <i>everything after the first eight bytes</i> (so, total minus 8,
 * not total and not the payload), while the data chunk size counts the samples
 * only. Getting the first one wrong produces a file that most players open
 * happily and some refuse, which is the worst possible failure mode.</p>
 *
 * <p><b>Reference — Microsoft/IBM Multimedia Programming Interface and Data
 * Specifications 1.0</b>, the RIFF WAVE definition:
 * https://www.mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html</p>
 */
public final class WavAudio
{
    /** Bytes in the canonical 16-bit PCM header, before the sample data. */
    public static final int HEADER_BYTES = 44;

    /** Bits per sample this writer emits. */
    public static final int BITS_PER_SAMPLE = 16;

    /** Channel count this writer emits. Mono — see {@link #wav}. */
    public static final int CHANNELS = 1;

    /** WAVE format tag 1: uncompressed PCM. */
    private static final int FORMAT_PCM = 1;

    /** Size of a PCM {@code fmt } chunk body, in bytes. */
    private static final int FMT_CHUNK_BYTES = 16;

    /** Bytes per sample, derived so the two constants cannot drift apart. */
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;

    /** Bytes the RIFF chunk size does not count: "RIFF" plus the size field. */
    private static final int RIFF_PREAMBLE_BYTES = 8;

    /** Mask for one byte. */
    private static final int BYTE_MASK = 0xFF;

    private WavAudio()
    {
        // format holder
    }

    /**
     * Wraps mono 16-bit samples in a complete, playable WAVE file.
     *
     * <p>Mono is not a limitation being papered over. A stereo buffer would
     * need a pan, a pan needs a listener, and a listener is the 3D mixer this
     * port does not have — see {@code I_AudioPort}. One channel is the honest
     * shape of what the engine can currently produce.</p>
     *
     * @param samples the PCM samples; must not be null, may be empty
     * @param sampleRate samples per second; must be positive
     * @return a complete WAVE file as bytes, {@link #HEADER_BYTES} plus two
     *     bytes per sample
     * @throws IllegalArgumentException if the samples are null or the rate is
     *     not positive — both are programming errors in the caller rather than
     *     runtime conditions, so unlike the audio port itself this does throw
     */
    public static byte[] wav(final short[] samples, final int sampleRate)
    {
        if (samples == null)
        {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (sampleRate <= 0)
        {
            throw new IllegalArgumentException("sampleRate must be positive, got " + sampleRate);
        }
        final int dataBytes = samples.length * BYTES_PER_SAMPLE;
        final byte[] out = new byte[HEADER_BYTES + dataBytes];
        int at = 0;
        at = ascii(out, at, "RIFF");
        // Total minus the eight bytes already written — the field that is
        // wrong in half the WAV writers ever published.
        at = int32(out, at, out.length - RIFF_PREAMBLE_BYTES);
        at = ascii(out, at, "WAVE");
        at = ascii(out, at, "fmt ");
        at = int32(out, at, FMT_CHUNK_BYTES);
        at = int16(out, at, FORMAT_PCM);
        at = int16(out, at, CHANNELS);
        at = int32(out, at, sampleRate);
        // Byte rate and block align are both derivable, and both are stored
        // anyway because the format stores them. A player that trusts them over
        // the other fields must still be told the truth.
        at = int32(out, at, sampleRate * CHANNELS * BYTES_PER_SAMPLE);
        at = int16(out, at, CHANNELS * BYTES_PER_SAMPLE);
        at = int16(out, at, BITS_PER_SAMPLE);
        at = ascii(out, at, "data");
        at = int32(out, at, dataBytes);
        for (final short sample : samples)
        {
            at = int16(out, at, sample);
        }
        return out;
    }

    // Writes ASCII bytes of a four-character chunk tag. Not getBytes(UTF_8):
    // these are format literals, not text, and the file says ASCII.
    private static int ascii(final byte[] out, final int offset, final String tag)
    {
        int at = offset;
        for (int index = 0; index < tag.length(); index++)
        {
            out[at] = (byte) tag.charAt(index);
            at++;
        }
        return at;
    }

    // Writes a 32-bit little-endian value and returns the next offset.
    private static int int32(final byte[] out, final int offset, final int value)
    {
        out[offset] = (byte) (value & BYTE_MASK);
        out[offset + 1] = (byte) ((value >> 8) & BYTE_MASK);
        out[offset + 2] = (byte) ((value >> 16) & BYTE_MASK);
        out[offset + 3] = (byte) ((value >> 24) & BYTE_MASK);
        return offset + 4;
    }

    // Writes a 16-bit little-endian value and returns the next offset.
    private static int int16(final byte[] out, final int offset, final int value)
    {
        out[offset] = (byte) (value & BYTE_MASK);
        out[offset + 1] = (byte) ((value >> 8) & BYTE_MASK);
        return offset + 2;
    }
}
