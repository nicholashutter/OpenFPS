/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

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

    // NetworkPacketEvent targets G_, but there is nothing to route it to
    // until Phase 3 wires tic-command extraction and snapshot delta into
    // I_NetworkPort. Until then the base-class no-op onEvent is correct.
}
