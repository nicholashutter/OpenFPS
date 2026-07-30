/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;

import com.openfps.engine.audio.AudioVolume;
import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.audio.port.SoundId;
import com.openfps.engine.audio.synth.SoundBank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real sound output: {@code Gdx.audio} behind {@link I_AudioPort}.
 *
 * <h2>Why this is in {@code :gdxshared} and not twice in the launchers</h2>
 *
 * <p>Everything below is libGDX <b>core</b> — {@code Gdx.audio},
 * {@code Gdx.files}, {@code Sound} — and none of it names a backend. LWJGL3 and
 * the Android backend supply different implementations of the same three
 * interfaces, and the two subtle properties this class has to get right (when
 * the audio device exists, and what to do when it does not) are the same two
 * properties on both. That is the module's stated purpose, and
 * {@code FramebufferPresenter} is the precedent: duplicating this per platform
 * would duplicate exactly the code most likely to drift and least likely to be
 * noticed drifting.</p>
 *
 * <p>What is genuinely per-platform is <b>one argument</b>: where a temporary
 * file may be written. {@code :desktop} passes the JVM temp directory and
 * {@code :android} passes the Activity's cache directory, each from its own
 * adapter factory. That is the whole platform-specific surface of audio.</p>
 *
 * <h2>Everything is lazy, and that is not an optimisation</h2>
 *
 * <p><b>{@code Gdx.audio} is null when {@link #init()} runs.</b> On desktop the
 * libGDX application does not exist until {@code GdxWindowPort.runFrameLoop}
 * constructs {@code Lwjgl3Application}, and that happens long after
 * {@code EngineMain.start} has brought up the subsystems — {@code AudioSubsystem
 * .onInit} is several steps earlier. An implementation that loaded its sounds in
 * {@code init()} would therefore fail on every desktop run, permanently and
 * silently, and would look like a broken sound file rather than a broken
 * ordering.</p>
 *
 * <p>So the sound is baked on <b>first play</b>, by which time a shot has been
 * fired, which means a window exists, which means the audio device does too. A
 * failed attempt is not latched: if {@code Gdx.audio} is still absent the next
 * shot tries again, because "not started yet" is a temporary condition and
 * disabling audio forever over it would be the same permanent-silent-failure
 * bug in a different place. The warning is logged once regardless, so a
 * genuinely broken setup does not scroll the console.</p>
 *
 * <p><b>Lazy is not the same as late</b>, and on Android the difference is
 * audible. {@code Gdx.audio.newSound} maps to {@code SoundPool.load}, which
 * returns an id immediately and finishes loading on its own thread some tens of
 * milliseconds later; a {@code play} inside that window is dropped outright.
 * Baking on the first shot therefore silenced the first shot — and the two
 * after it. {@link #preload()} is the fix: the same bake, at a seam where a
 * device exists and nothing is about to fire. Desktop's OpenAL loads
 * synchronously and never had the symptom, which is exactly why it went
 * unnoticed until an emulator.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #play} is called from the game loop thread — a shot is a simulation
 * event — while libGDX's audio belongs to whichever thread built the
 * application. Both shipped backends tolerate {@code Sound.play} off the render
 * thread; what neither guarantees is two threads racing to <i>create</i> the
 * same sound. One lock around the bake makes that impossible, and playing a
 * loaded sound stays outside it so a shot never waits on a file write.</p>
 *
 * <p><b>Nothing here throws</b>, per the port contract: no device, no writable
 * cache directory, a decoder that refuses the file — each is caught, logged
 * once, and leaves the game running silently. CI has no sound card and the game
 * must not care.</p>
 */
public final class GdxAudioPort implements I_AudioPort
{
    private static final Logger LOG = LoggerFactory.getLogger(GdxAudioPort.class);

    /** Where a temporary WAV may be written — the one per-platform decision. */
    private final File cacheDirectory;

    /** Guards {@link #loaded} and every {@code Sound} lifecycle transition. */
    private final Object bakeLock = new Object();

    /** Sounds baked so far. MUTABLE: filled lazily under {@link #bakeLock}. */
    private final Map<SoundId, Sound> loaded = new EnumMap<>(SoundId.class);

    /** The clamped master volume. MUTABLE: read on every play. */
    private volatile float volume = AudioVolume.FULL;

    /** Whether {@link #init} has run and {@link #shutdown} has not. MUTABLE. */
    private volatile boolean running;

    /** Whether a real failure has been logged. MUTABLE: latched by warnOnce. */
    private volatile boolean warned;

    /**
     * Whether the "no device yet" note has been logged. MUTABLE.
     *
     * <p>A second flag rather than reusing {@link #warned}, because the two
     * conditions differ in kind. "The frame loop has not started" is expected
     * and transient; "the decoder refused the file" is a real fault. Sharing one
     * latch would let the harmless one swallow the serious one, which is the
     * failure mode where you learn about a broken sound six months later.</p>
     */
    private volatile boolean deviceWarned;

    /**
     * Creates the port over a directory it may write a temporary file into.
     *
     * @param cacheDir where the staged WAV goes — the JVM temp directory on
     *     desktop, the Activity cache directory on Android. May be null or
     *     unwritable; that costs the sound, not the run
     */
    public GdxAudioPort(final File cacheDir)
    {
        this.cacheDirectory = cacheDir;
    }

    @Override
    public void init()
    {
        this.running = true;
        // Nothing is opened here on purpose — see the class Javadoc. Saying so
        // in the log because "audio initialised" followed by silence for the
        // first few seconds of a run is otherwise indistinguishable from a
        // failure.
        LOG.info("Audio ready — sounds are generated and loaded on first use,"
            + " because there is no libGDX audio device until the frame loop starts");
    }

    @Override
    public void shutdown()
    {
        this.running = false;
        synchronized (bakeLock)
        {
            for (final Sound sound : loaded.values())
            {
                disposeQuietly(sound);
            }
            loaded.clear();
        }
        LOG.info("Audio shut down");
    }

    @Override
    public void play(final SoundId sound)
    {
        if (sound == null || !running)
        {
            return;
        }
        final Sound voice = bake(sound);
        if (voice == null)
        {
            return;
        }
        try
        {
            // Outside the lock: a shot must not wait behind a file write, and
            // overlapping plays are meant to overlap in the mix rather than
            // queue. Sound.play is fire-and-forget on both backends — the
            // returned voice id is dropped because nothing here stops an
            // individual playback (I_AudioPort#play).
            voice.play(volume);
        }
        catch (final RuntimeException e)
        {
            // A device unplugged mid-session lands here. One log, then silence,
            // rather than one log per shot.
            warnOnce("Could not play " + sound + ": {}", e.toString());
        }
    }

    @Override
    public void preload()
    {
        if (!running)
        {
            return;
        }
        // Every sound, not a named one: a second SoundId should be warmed by
        // existing at all rather than by someone remembering to add it here.
        // bake() is the same call play() makes, so a device that is still
        // absent costs one null check and the next attempt tries again.
        for (final SoundId sound : SoundId.values())
        {
            bake(sound);
        }
    }

    @Override
    public void stopAll()
    {
        synchronized (bakeLock)
        {
            for (final Sound sound : loaded.values())
            {
                try
                {
                    sound.stop();
                }
                catch (final RuntimeException e)
                {
                    warnOnce("Could not stop a sound: {}", e.toString());
                }
            }
        }
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
     * Returns whether a sound has actually been loaded against a real device.
     *
     * <p>False until the first successful {@link #play}, which is honest rather
     * than pessimistic: before then nothing has proved a device exists, and this
     * method's whole job is to distinguish "audio works" from "audio is silently
     * doing nothing".</p>
     *
     * @return true once at least one sound is loaded
     */
    @Override
    public boolean isAudible()
    {
        synchronized (bakeLock)
        {
            return !loaded.isEmpty();
        }
    }

    // The Sound for one id, generating and loading it on first request.
    // Returns null whenever it cannot be had — no device yet, no writable
    // directory, a decoder that refused — and never throws.
    private Sound bake(final SoundId sound)
    {
        synchronized (bakeLock)
        {
            final Sound existing = loaded.get(sound);
            if (existing != null)
            {
                return existing;
            }
            // NOT latched as "attempted". Gdx.audio being null means the frame
            // loop has not started, which is temporary; latching would turn a
            // timing condition into a permanent mute.
            if (Gdx.audio == null || Gdx.files == null)
            {
                if (!deviceWarned)
                {
                    this.deviceWarned = true;
                    LOG.warn("No libGDX audio device yet — {} stays silent for now", sound);
                }
                return null;
            }
            final File staged = stage(sound);
            if (staged == null)
            {
                return null;
            }
            try
            {
                final Sound baked = Gdx.audio.newSound(
                    Gdx.files.absolute(staged.getAbsolutePath()));
                loaded.put(sound, baked);
                LOG.info("Loaded {} — {} samples of generated PCM at {} Hz, staged at {}",
                    sound, Integer.valueOf(SoundBank.sampleCount(sound)),
                    Integer.valueOf(SoundBank.sampleRate(sound)), staged);
                return baked;
            }
            catch (final RuntimeException e)
            {
                // libGDX with no device returns a do-nothing Sound rather than
                // throwing, so reaching here means something worse — a missing
                // native, a decoder refusing the file. Either way: log, go
                // silent, keep playing the game.
                warnOnce("Could not load " + sound + ", running silent: {}", e.toString());
                return null;
            }
        }
    }

    // Writes the generated WAV for one sound into the cache directory, and
    // returns the file. Returns null on any failure, having logged it once.
    //
    // A file at all because neither backend will take samples: Audio.newSound
    // wants a FileHandle, and the one API that does take raw PCM
    // (newAudioDevice) blocks the calling thread for the duration of the sound.
    // WavAudio explains the container choice.
    private File stage(final SoundId sound)
    {
        if (cacheDirectory == null)
        {
            warnOnce("No cache directory — {} cannot be staged, running silent", sound);
            return null;
        }
        final File file = new File(cacheDirectory, SoundBank.fileName(sound));
        // Rewritten every run rather than reused if present. It is a few KB, it is
        // deterministic, and a truncated file left by a killed process would
        // otherwise be a permanently broken sound that reinstalling does not fix.
        try
        {
            // BEFORE the stream is opened, not inside the try-with-resources:
            // FileOutputStream fails outright if the parent does not exist, so
            // creating it afterwards would be creating it too late.
            cacheDirectory.mkdirs();
            try (OutputStream out = new FileOutputStream(file))
            {
                // SoundBank, not a synthesis class. THIS LINE WAS THE BUG the
                // second sound found: it used to name BlasterSound outright, so
                // every SoundId this adapter was handed staged the player's own
                // weapon. It loaded, warmed and played on cue, and the only symptom
                // would have been that incoming fire sounded like your own gun.
                //
                // An adapter has no business knowing what a sound IS. It knows
                // where a file may be written and how this platform plays one.
                out.write(SoundBank.wav(sound));
            }
        }
        catch (final IOException | RuntimeException e)
        {
            warnOnce("Could not stage " + sound + ", running silent: {}", e.toString());
            return null;
        }
        return file;
    }

    // Logs a warning the first time only. Audio failures are per-shot, and a
    // shot happens five times a second — an unthrottled warning would bury the
    // rest of the log within a minute of a device being unplugged.
    private void warnOnce(final String format, final Object detail)
    {
        if (warned)
        {
            return;
        }
        this.warned = true;
        LOG.warn(format, detail);
    }

    // Disposes a sound, swallowing whatever a half-torn-down backend throws.
    // Called from shutdown, which is often the tail of an abrupt exit.
    private static void disposeQuietly(final Sound sound)
    {
        try
        {
            sound.dispose();
        }
        catch (final RuntimeException e)
        {
            LOG.debug("Ignoring a failure disposing a sound: {}", e.toString());
        }
    }

    /** Returns a debug rendering of the port's state. */
    @Override
    public String toString()
    {
        return "GdxAudioPort{running=" + running + ", audible=" + isAudible()
            + ", volume=" + volume + "}";
    }
}
