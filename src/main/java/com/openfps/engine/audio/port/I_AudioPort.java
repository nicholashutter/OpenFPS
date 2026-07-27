/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.port;

/**
 * S_ Port interface — audio playback.
 * Stubbed for Phase 0; implementations wired in Phase 6.
 *
 * ====================================================================
 *  3D AUDIO MATH (Phase 6+ — references below)
 * ====================================================================
 *
 *  Full math is documented in src/main/java/com/openfps/engine/audio/README.md.
 *  Summary:
 *
 *  1. INVERSE-SQUARE DISTANCE FALLOFF:
 *       distance² = dx*dx + dy*dy + dz*dz
 *       clampedDistance² = max(MIN_DISTANCE², distance²)
 *       loudness = sourceVolume / clampedDistance²
 *     Source: OpenAL 1.1 spec §5.2 "Distance Attenuation"
 *     https://www.openal.org/documentation/openal-1.1-specification.pdf
 *     Alternative (more musical): linear falloff inside [MIN_DIST, MAX_DIST]
 *     and 1/r² outside. The "rolloff factor" tunes the curve shape.
 *
 *  2. STEREO PANNING:
 *       sourceDir  = sourcePos - listenerPos    (normalized)
 *       listenerForward = (cos(listenerYaw), sin(listenerYaw))
 *       dot = sourceDir · listenerForward
 *       pan = sin(acos(dot)) × signOfCross     // [-1, +1]
 *     Source: "3D Audio for Games" — Dyon Dutil
 *     https://www.dspdimension.com/
 *     In practice, game engines use a lookup table for the cos/sin calls.
 *
 *  3. DOPPLER SHIFT (for moving sources):
 *       fObserved = fSource × (c + vListener) / (c + vSource)
 *     where c = speed of sound ≈ 343 m/s.
 *     Always clamp: 0.5 × fSource ≤ fObserved ≤ 2.0 × fSource
 *     Source: https://en.wikipedia.org/wiki/Doppler_effect
 *
 *  4. VOICE PRIORITY (for culling when over voice limit):
 *       priority = distanceWeight × volume × recencyWeight
 *     Lowest-priority voice culled first.
 *
 *  All audio math is float-based (precision matters for audible results).
 *  Unlike the game loop, audio is not lockstep — slight float differences
 *  between machines are fine. We only need determinism in the GAMEPLAY
 *  state machine, not in the sound mixer.
 */
public interface I_AudioPort
{
    /**
     * Plays a sound effect at a 3D world position.
     *
     * @param soundId internal sound identifier
     * @param x fixed-point x
     * @param y fixed-point y
     * @param z fixed-point z
     */
    void playSfx(int soundId, int x, int y, int z);

    /**
     * Starts background music from a lump name.
     *
     * @param lumpName name of the music lump
     */
    void playMusic(String lumpName);

    /**
     * Stops all audio playback.
     */
    void stopAll();

    /**
     * Initializes the audio subsystem.
     */
    void init();

    /**
     * Shuts down the audio subsystem.
     */
    void shutdown();
}
