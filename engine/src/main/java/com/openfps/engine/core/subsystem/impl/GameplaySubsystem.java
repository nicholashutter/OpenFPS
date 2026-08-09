/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.MapLoadEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.gameplay.port.I_GameplayPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gameplay subsystem (P_). Wraps an {@link I_GameplayPort} and routes
 * events to it.
 */
public final class GameplaySubsystem extends Subsystem
{
    private static final Logger LOG = LoggerFactory.getLogger(GameplaySubsystem.class);

    private final I_GameplayPort port;

    public GameplaySubsystem(final I_GameplayPort port)
    {
        super(SubsystemId.P_);

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
        if (event instanceof TickEvent tick)
        {
            port.tick(tick.ticNumber());
        }
        else if (event instanceof MapLoadEvent load)
        {
            final boolean ok = port.loadMap(load.mapName());

            if (!ok)
            {
                LOG.warn("Map load returned false for '{}'", load.mapName());
            }
        }
        // InputSampledEvent also targets P_, but there is nothing to route
        // it to until Phase 4 wires TicCmd decoding into I_GameplayPort.
    }
}
