/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.audio.adapter;

import com.openfps.engine.audio.port.I_AudioPort;

/**
 * S_ Null adapter for audio.
 * No sound is played; used for headless testing.
 */
public final class NullAudioPort implements I_AudioPort
{
    @Override
    public void playSfx(final int soundId, final int x, final int y, final int z)
    {
        // no-op
    }

    @Override
    public void playMusic(final String lumpName)
    {
        // no-op
    }

    @Override
    public void stopAll()
    {
        // no-op
    }

    @Override
    public void init()
    {
        // no-op
    }

    @Override
    public void shutdown()
    {
        // no-op
    }
}
