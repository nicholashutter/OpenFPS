/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.port;

/**
 * S_ Port interface — audio playback.
 * Stubbed for Phase 0; implementations wired in Phase 6.
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
    void playSfx(final int soundId, final int x, final int y, final int z);

    /**
     * Starts background music from a lump name.
     *
     * @param lumpName name of the music lump
     */
    void playMusic(final String lumpName);

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
