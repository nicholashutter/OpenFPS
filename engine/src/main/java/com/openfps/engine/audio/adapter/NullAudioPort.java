/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.adapter;

import java.util.concurrent.atomic.AtomicLong;

import com.openfps.engine.audio.AudioVolume;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.audio.port.SoundId;

/**
 * S_ The silent audio port — every headless run, every test, and every
 * platform that has not wired a real backend yet.
 *
 * <p>This is the default and it is reached constantly: {@code --headless},
 * CI, {@code NullAdapterFactory}, {@code DesktopAdapterFactory} (which stays
 * display-free on purpose, exactly as it does for the window and input ports),
 * and any {@code DemoGameplayPort} nobody handed a real port to. It must
 * therefore be completely inert with respect to the outside world, and it
 * is: no device, no file, no thread, no allocation after construction.</p>
 *
 * <h2>Why it remembers things, when "null adapter" suggests it should not</h2>
 *
 * <p>Two of the port's methods are questions rather than commands —
 * {@link #masterVolume()} has to read back what {@link #setMasterVolume(float)}
 * clamped, or the contract is a lie in the one implementation every test uses.
 * Once the volume is stored, counting plays is one more field and it is what
 * makes the interesting assertions possible without a sound card: that a held
 * trigger produces one sound per shot rather than one per tic, that a frozen
 * match makes no noise, that firing before a match starts is silent. Those are
 * properties of the <i>engine</i>, and this is how CI can check them.</p>
 *
 * <p>{@code MemoryUserProfilePort} is the precedent — the null-backend member
 * of that family is in-memory rather than amnesiac, for the same reason.</p>
 *
 * <p><b>Thread-safe</b>, because {@code I_AudioPort.play} is documented as
 * callable from any thread and the game loop is not the only caller in a test.
 * An {@link AtomicLong} for the counter and a volatile for the rest is the
 * whole of it — there is no compound state to keep consistent.</p>
 */
public final class NullAudioPort implements I_AudioPort
{
    /** How many sounds have been asked for. MUTABLE: bumped by {@link #play}. */
    private final AtomicLong plays = new AtomicLong();

    /** The clamped master volume. MUTABLE: set by {@link #setMasterVolume}. */
    private volatile float volume = AudioVolume.FULL;

    /** The most recent sound asked for, or null. MUTABLE: set by {@link #play}. */
    private volatile SoundId lastSound;

    @Override
    public void init()
    {
        // Nothing to open. Deliberately not logged: this port is initialised in
        // every test in the repository and a line each would drown the output
        // of the ones that matter.
    }

    @Override
    public void shutdown()
    {
        // Nothing to close, and the counters are deliberately NOT reset — a
        // test that asserts on what a full session played needs them to survive
        // the session's own teardown.
    }

    @Override
    public void play(final SoundId sound)
    {
        if (sound == null)
        {
            return;
        }
        this.lastSound = sound;
        plays.incrementAndGet();
    }

    @Override
    public void stopAll()
    {
        // Nothing is playing, by construction. The play count is left alone:
        // "how many shots were fired" and "what is audible now" are different
        // questions, and only the first one is answerable here.
    }

    @Override
    public void setMasterVolume(final float requested)
    {
        this.volume = AudioVolume.clamp(requested);
    }

    @Override
    public float masterVolume()
    {
        return volume;
    }

    /**
     * Returns false — always, and that is the useful part.
     *
     * <p>A launcher that logs "no audio device — running silent" gets its
     * answer from here, and a test can assert the degraded path is taken
     * without needing a machine that has no sound card.</p>
     *
     * @return false
     */
    @Override
    public boolean isAudible()
    {
        return false;
    }

    /**
     * Returns how many times {@link #play} has been called with a real sound.
     *
     * @return the play count, never negative
     */
    public long playCount()
    {
        return plays.get();
    }

    /**
     * Returns the last sound asked for, or null if none ever was.
     *
     * @return the most recently played sound, or null
     */
    public SoundId lastSound()
    {
        return lastSound;
    }

    /** Returns a debug rendering of what this port has been asked to do. */
    @Override
    public String toString()
    {
        return "NullAudioPort{silent, plays=" + plays.get() + ", volume=" + volume + "}";
    }
}
