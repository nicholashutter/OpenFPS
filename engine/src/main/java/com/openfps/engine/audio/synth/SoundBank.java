/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.synth;

import java.util.Locale;

import com.openfps.engine.audio.port.SoundId;

/**
 * S_ What every {@link SoundId} sounds like, as playable bytes.
 *
 * <h2>This is the seam the second sound proved was missing</h2>
 *
 * <p>With one sound the arrangement looked complete and was not. {@code SoundId}
 * named the sounds, {@code audio/synth} generated them, {@code I_AudioPort} played
 * them — and <b>nothing joined the second to the first</b>. So the only adapter
 * that existed reached into {@link BlasterSound} directly, on every path, for
 * every id it was ever handed:</p>
 *
 * <pre>
 *   out.write(WavAudio.wav(BlasterSound.samples(), BlasterSound.sampleRate()));
 * </pre>
 *
 * <p>That is correct code for a one-sound engine and it is a bug the instant there
 * are two. {@code SoundId.BOT_WEAPON_FIRE} would have compiled, been warmed by
 * {@code preload}'s loop over {@code values()}, been staged to its own file by
 * {@code fileNameFor}'s switch, loaded against a real device, and played the
 * player's own blaster — working audio, a green build, and no way to tell from
 * anywhere except your ears. <b>Every adapter would have had the same bug</b>,
 * because every adapter would have had to make the same choice, which is precisely
 * the duplicated untested arithmetic a port exists to prevent.
 * {@code I_AudioPort}'s own Javadoc makes that argument about world positions and
 * then this file did not exist to make it about samples.</p>
 *
 * <p>So the division is now the one the port claims: <b>the engine says what a
 * sound is, an adapter says how this platform plays bytes.</b> An adapter names no
 * synthesis class. Adding a third sound is a value on the enum and a case here,
 * both in {@code :engine}, both testable with no device — and no adapter changes at
 * all.</p>
 *
 * <h2>Why bytes and a file name rather than samples</h2>
 *
 * <p>Because that is what the backends will take, and there is no point handing
 * across something every caller then has to convert. {@link WavAudio} has the
 * argument: libGDX's {@code Audio.newSound} accepts a {@code FileHandle} and
 * nothing else, Android's {@code SoundPool} underneath it wants a file descriptor,
 * and the one API that does take raw PCM blocks the calling thread for the
 * duration of the sound. So the shortest honest path is a 44-byte header and a
 * temp file, and the only per-platform decision left is <i>which directory</i> —
 * which is exactly the one argument {@code GdxAudioPort} takes.</p>
 *
 * <p>{@link #fileName} is here for the same reason: it was a {@code switch} in the
 * adapter, and a second adapter would have written a second one. It is a property
 * of the sound, not of the platform.</p>
 *
 * <h2>Allocation and threading</h2>
 *
 * <p>{@link #wav} allocates and is called <b>once per sound per process</b>, when
 * an adapter bakes. Nothing here holds state, so it is safe from any thread.</p>
 */
public final class SoundBank
{
    /** Prefix on every staged file name, so a shared temp directory stays legible. */
    private static final String FILE_PREFIX = "openfps-";

    /** Extension of a staged sound. Uncompressed PCM — see {@link WavAudio}. */
    private static final String FILE_SUFFIX = ".wav";

    private SoundBank()
    {
        // catalogue holder
    }

    /**
     * Returns one sound as a complete, playable WAVE file.
     *
     * <p><b>A {@code switch} with no default, deliberately.</b> Every value of the
     * enum is listed, so adding a value is a compile error here rather than a
     * silent fallback to whichever sound happened to be first — which is the exact
     * failure this class was written to remove. A null is answered with the
     * player's weapon rather than an exception, because {@code I_AudioPort.play}
     * promises that an unknown sound is ignored rather than rejected and an
     * adapter must be able to keep that promise without a null check of its
     * own.</p>
     *
     * @param sound which sound to bake; null is treated as {@link
     *     SoundId#WEAPON_FIRE}
     * @return a fresh WAVE file as bytes, ready to hand to a platform decoder
     */
    public static byte[] wav(final SoundId sound)
    {
        if (sound == SoundId.BOT_WEAPON_FIRE)
        {
            return WavAudio.wav(CarbineSound.samples(), CarbineSound.sampleRate());
        }
        if (sound == SoundId.SUPER_WEAPON_FIRE)
        {
            return WavAudio.wav(SuperBlasterSound.samples(), SuperBlasterSound.sampleRate());
        }
        if (sound == SoundId.SUPER_BLASTER_READY)
        {
            return WavAudio.wav(PowerChimeSound.readySamples(), PowerChimeSound.sampleRate());
        }
        if (sound == SoundId.SUPER_BLASTER_SPENT)
        {
            // The same chime backwards, which is why one class serves both ids —
            // and why the two buffers are genuinely different bytes rather than one
            // sound reached by two names. SoundBankTest asserts exactly that.
            return WavAudio.wav(PowerChimeSound.spentSamples(), PowerChimeSound.sampleRate());
        }
        return WavAudio.wav(BlasterSound.samples(), BlasterSound.sampleRate());
    }

    /**
     * Returns the sample rate one sound is meant to be played at.
     *
     * @param sound which sound; null is treated as {@link SoundId#WEAPON_FIRE}
     * @return samples per second
     */
    public static int sampleRate(final SoundId sound)
    {
        if (sound == SoundId.BOT_WEAPON_FIRE)
        {
            return CarbineSound.sampleRate();
        }
        if (sound == SoundId.SUPER_WEAPON_FIRE)
        {
            return SuperBlasterSound.sampleRate();
        }
        if (sound == SoundId.SUPER_BLASTER_READY || sound == SoundId.SUPER_BLASTER_SPENT)
        {
            return PowerChimeSound.sampleRate();
        }
        return BlasterSound.sampleRate();
    }

    /**
     * Returns how many samples one sound holds.
     *
     * <p>For the log line an adapter writes when it loads a sound. That line is
     * the only evidence a user has that audio came up at all — see
     * {@code I_AudioPort.isAudible} on why silent success and silent failure must
     * not look alike — and it was reporting the blaster's length for every id.</p>
     *
     * @param sound which sound; null is treated as {@link SoundId#WEAPON_FIRE}
     * @return the sample count
     */
    public static int sampleCount(final SoundId sound)
    {
        if (sound == SoundId.BOT_WEAPON_FIRE)
        {
            return CarbineSound.sampleCount();
        }
        if (sound == SoundId.SUPER_WEAPON_FIRE)
        {
            return SuperBlasterSound.sampleCount();
        }
        if (sound == SoundId.SUPER_BLASTER_READY || sound == SoundId.SUPER_BLASTER_SPENT)
        {
            // One number for both, because the chime is two notes of equal length
            // whichever order they are in. That is a property PowerChimeSound goes
            // out of its way to have — see its sampleCount().
            return PowerChimeSound.sampleCount();
        }
        return BlasterSound.sampleCount();
    }

    /**
     * Returns the file name one sound is staged under.
     *
     * <p>Derived from the enum constant rather than switched, so a new sound gets a
     * distinct file by existing. It used to be a {@code switch} in the adapter,
     * which meant a second adapter would have written a second one and the two
     * could have disagreed about which file held which sound — while sharing a
     * temp directory.</p>
     *
     * <p>Stable across runs on purpose: the file is rewritten every bake, so a
     * fixed name reuses one 8 KB file instead of littering a cache directory with
     * one per launch.</p>
     *
     * @param sound which sound; null yields a name for {@link SoundId#WEAPON_FIRE}
     * @return a file name with no directory part
     */
    public static String fileName(final SoundId sound)
    {
        final SoundId named;
        if (sound == null)
        {
            named = SoundId.WEAPON_FIRE;
        }
        else
        {
            named = sound;
        }
        return FILE_PREFIX + named.name().toLowerCase(Locale.ROOT).replace('_', '-')
            + FILE_SUFFIX;
    }
}
