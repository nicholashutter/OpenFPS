/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.port;

/**
 * S_ Port interface — the engine's sound output.
 *
 * <p>Six methods, one of which is a getter. That is the whole port, and the
 * smallness is the point: this is what the demo genuinely needs — fire a
 * weapon and hear it — expressed so that {@code :engine} names no audio API,
 * no file format and no device.</p>
 *
 * <h2>What replaced the Phase 0 stub, and why</h2>
 *
 * <p>The previous version of this interface was {@code playSfx(int soundId,
 * int x, int y, int z)} plus {@code playMusic(String lumpName)}, wrapped in
 * eighty lines of inverse-square attenuation, stereo panning and Doppler
 * formulae. None of it was implemented, and both signatures were wrong in ways
 * that would have had to be undone before anything could use them:</p>
 *
 * <ul>
 *   <li><b>The position arguments presumed a mixer that does not exist.</b> A
 *       port takes what the implementation can honour. Neither libGDX
 *       {@code Sound} nor Android {@code SoundPool} takes a world position —
 *       they take a volume and a pan — so every adapter would have had to
 *       carry its own listener transform to convert one into the other, which
 *       is precisely the duplicated, untested arithmetic a port exists to
 *       prevent. Positional audio is a <b>documented future extension</b>
 *       (see {@code audio/README.md}), and when it arrives it arrives as a
 *       listener pose plus a source position — not as three ints whose frame
 *       of reference nobody wrote down.</li>
 *   <li><b>{@code playMusic(String lumpName)} named a WAD lump.</b>
 *       {@code docs/ASSETS.md} § 8 is "No IWADs, ever", so there are no
 *       {@code DS*} or {@code D_*} lumps and never will be. A parameter named
 *       after a container the project has ruled out is worse than no method
 *       at all.</li>
 * </ul>
 *
 * <h2>Contract every implementation owes</h2>
 *
 * <ul>
 *   <li><b>Nothing here throws.</b> Not on a machine with no sound card, not
 *       in a headless JVM, not when a sound fails to load. CI has no audio
 *       device, and a game that dies because the speakers are missing is worse
 *       than a silent one. Log once and continue — the log matters, because a
 *       silent failure and working audio played into a muted mixer look
 *       identical from the outside.</li>
 *   <li><b>{@link #play} is callable from any thread.</b> It is called from the
 *       game loop thread — a shot is a simulation event — while the platform's
 *       audio backend generally belongs to the render thread. An
 *       implementation that cannot cross that boundary must serialise
 *       internally; it must not make the caller do it.</li>
 *   <li><b>{@link #play} never blocks for the duration of the sound.</b> It
 *       starts playback and returns. A tic that waited 180 ms for a blaster to
 *       finish would miss ten of them at 60 Hz.</li>
 *   <li><b>Volume is clamped, not rejected.</b> See
 *       {@link #setMasterVolume(float)}.</li>
 *   <li><b>{@link #init()} and {@link #shutdown()} are idempotent.</b> The
 *       lifecycle owner is {@code AudioSubsystem}, but a platform launcher may
 *       reasonably tear down twice on an abrupt exit — Android's
 *       {@code onDestroy} can arrive with the loop still running.</li>
 * </ul>
 *
 * <p><b>Audio is not lockstep.</b> Unlike everything the simulation touches,
 * nothing here has to be identical between two peers: a sound is a consequence
 * of a tic and no tic reads one back. That is what allows float volumes,
 * platform mixers and a frame of latency, none of which the gameplay state
 * machine could tolerate.</p>
 */
public interface I_AudioPort
{
    /**
     * Prepares the audio device. Called once, by {@code AudioSubsystem}.
     *
     * <p>Must not throw when there is no device. An implementation that cannot
     * open one logs and leaves {@link #isAudible()} false; every later call is
     * then a well-defined no-op rather than a crash.</p>
     */
    void init();

    /**
     * Releases the audio device and every sound loaded against it.
     *
     * <p>Called once, by {@code AudioSubsystem}. Must tolerate being called
     * without a matching successful {@link #init()}.</p>
     */
    void shutdown();

    /**
     * Plays one sound, once, at the current master volume.
     *
     * <p>Fire-and-forget: there is no handle to stop this individual playback,
     * because nothing in the demo needs one and a handle nobody holds is a leak
     * waiting to be found. {@link #stopAll()} is the escape hatch.</p>
     *
     * <p>Overlapping calls overlap in the mix — firing again before the last
     * shot has decayed must not cut it off. That is what a weapon sounds like,
     * and it is also what {@code Sound.play} does on both backends, so no
     * implementation has to work for it.</p>
     *
     * @param sound which sound to play; a null or unknown value is ignored
     *     rather than rejected, because a caller cannot usefully handle "the
     *     speakers do not know that noise"
     */
    void play(SoundId sound);

    /**
     * Loads every sound this port can play, now, if it can.
     *
     * <p>An optional hint, not a lifecycle step: a port that loads eagerly in
     * {@link #init()} may do nothing here, and calling it twice must be
     * harmless. It exists because {@link #init()} runs before some platforms
     * have an audio device at all, so the only alternative to a hint is loading
     * on the first {@link #play} — and on Android that is measurably too late.
     * {@code SoundPool.load} is <b>asynchronous</b>: the sound is not playable
     * for some tens of milliseconds after it is created, and a shot fired
     * inside that window is dropped with nothing but a {@code play soundID not
     * READY} line in logcat to show for it. The first three shots of every
     * match were silent.</p>
     *
     * <p>Call it at a seam where a device certainly exists and nothing is about
     * to fire — entering a match, not leaving a menu. Must not throw and must
     * not block on anything slower than a small file write.</p>
     */
    void preload();

    /**
     * Silences everything currently playing.
     *
     * <p>For the seams where the world goes away underneath the sound: a match
     * ending, a menu opening, the application shutting down. Sounds already
     * started keep no state that outlives this call.</p>
     */
    void stopAll();

    /**
     * Sets the master volume applied to every subsequent {@link #play}.
     *
     * <p><b>Clamped, never rejected</b>, to {@code [0, 1]}, and a NaN is
     * treated as silence. The alternative — throwing on an out-of-range
     * volume — puts an exception on a settings slider, and a NaN that reaches
     * a mixer is a spectacularly loud bug on some backends and total silence
     * on others. {@code AudioVolume.clamp} is the one definition of that
     * arithmetic and every implementation must use it.</p>
     *
     * <p>Whether a change reaches sounds that are <b>already playing</b> is
     * deliberately unspecified: libGDX's {@code Sound} takes its volume at
     * {@code play} time, so on both shipped backends it does not. Nothing in
     * the demo turns the volume down mid-shot.</p>
     *
     * @param volume the requested volume; any float, including NaN
     */
    void setMasterVolume(float volume);

    /**
     * Returns the master volume in effect, always within {@code [0, 1]}.
     *
     * <p>Reads back the clamped value rather than the requested one — the port
     * tells you what it will actually do, not what you asked for.</p>
     *
     * @return the clamped master volume
     */
    float masterVolume();

    /**
     * Returns whether this port can actually make a noise.
     *
     * <p>False for the null adapter, for a headless JVM, and for a real
     * backend that failed to open a device or load its sounds. It exists so a
     * launcher can say "running silent" once at startup instead of leaving the
     * user to wonder, and so tests can assert the degraded path without a sound
     * card. It is <b>not</b> a precondition for {@link #play} — calling into a
     * silent port is legal and does nothing.</p>
     *
     * @return true if a real device is open and at least one sound is loaded
     */
    boolean isAudible();
}
