/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.RenderFrameEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.render.port.I_RenderPort;

/**
 * Render subsystem (R_). Wraps an {@link I_RenderPort}.
 */
public final class RenderSubsystem extends Subsystem
{
    private final I_RenderPort port;

    public RenderSubsystem(final I_RenderPort port)
    {
        super(SubsystemId.R_);
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

    @Override
    protected void onEvent(final I_EngineEvent event)
    {
        if (event instanceof RenderFrameEvent frame)
        {
            port.renderFrame(frame.frameNumber());
        }
    }
}
