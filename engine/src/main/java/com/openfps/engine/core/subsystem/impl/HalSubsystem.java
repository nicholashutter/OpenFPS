/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

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

    // No event type currently targets I_. Input sampling is pushed onto
    // the bus by the input port and routed to P_; a polling flow through
    // this subsystem arrives with the desktop HAL adapter (Phase 1.5).
}
