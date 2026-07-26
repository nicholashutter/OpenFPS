/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.InputSampledEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.hal.port.I_InputPort;

/**
 * Hardware abstraction subsystem (I_). Wraps input sampling; the
 * remaining HAL ports (Time, File, Network) are used directly by the
 * engine without going through this subsystem.
 */
public final class HalSubsystem extends Subsystem
{
    private final I_InputPort inputPort;

    public HalSubsystem(final I_InputPort inputPort)
    {
        super(SubsystemId.I_);
        this.inputPort = inputPort;
    }

    @Override
    protected void onInit()
    {
        inputPort.init();
    }

    @Override
    protected void onShutdown()
    {
        inputPort.shutdown();
    }

    @Override
    protected void onEvent(final I_EngineEvent event)
    {
        if (event instanceof InputSampledEvent)
        {
            // Input events flow from the HAL to the gameplay port directly
            // (the input port pushes them onto the bus). This onEvent is
            // a no-op for now; future phases will add a polling flow here.
        }
    }
}
