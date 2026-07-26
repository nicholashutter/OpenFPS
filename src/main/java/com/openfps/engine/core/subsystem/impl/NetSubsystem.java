/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.NetworkPacketEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.net.port.I_NetworkPort;

/**
 * Network subsystem (G_). Wraps an {@link I_NetworkPort}.
 */
public final class NetSubsystem extends Subsystem
{
    private final I_NetworkPort port;

    public NetSubsystem(final I_NetworkPort port)
    {
        super(SubsystemId.G_);
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
        if (event instanceof NetworkPacketEvent pkt)
        {
            // Phase 3 will route this through the G_ network layer for
            // tic command extraction and snapshot delta. For now, we
            // accept the event and let the port's discovery/tic methods
            // (called from the game loop) do the actual work.
            // No-op: just count it.
        }
    }
}
