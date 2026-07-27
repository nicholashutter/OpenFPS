/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.audio.port.I_AudioPort;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Audio subsystem (S_). Wraps an {@link I_AudioPort}.
 *
 * Phase 6 will add playSfx and playMusic events. For now, onEvent is
 * a no-op — the audio port's init/shutdown are the only handled calls.
 */
public final class AudioSubsystem extends Subsystem
{
    private final I_AudioPort port;

    public AudioSubsystem(final I_AudioPort port)
    {
        super(SubsystemId.S_);
        this.port = port;
    }

    @Override
    protected void onInit()
    {
        port.init();
    }

    @Override
    protected void onShutdown()
    {
        port.shutdown();
    }
}
