/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_InputPort;

/**
 * Null implementation of I_InputPort.
 * All inputs are zero — used for headless testing and AI-controlled tics.
 */
public final class NullInputPort implements I_InputPort
{
    private volatile boolean shutdownRequested;

    @Override
    public void sampleInput(final int ticIndex)
    {
        // all inputs are zero — no-op
    }

    @Override
    public boolean isShutdownRequested()
    {
        return shutdownRequested;
    }

    @Override
    public void init()
    {
        shutdownRequested = false;
    }

    @Override
    public void shutdown()
    {
        // no-op
    }

    /** Triggers shutdown on the next tic. Useful for testing. */
    public void requestShutdown()
    {
        shutdownRequested = true;
    }
}
