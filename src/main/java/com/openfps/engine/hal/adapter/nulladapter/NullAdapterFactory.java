/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.port.I_FilePort;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Headless adapter factory for unit testing and CI.
 * All adapters are null / in-memory — no OS or graphics calls.
 */
public final class NullAdapterFactory
{
    private static final Logger LOG = Logger.getLogger(NullAdapterFactory.class.getName());

    private final NullTimePort timePort = new NullTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final NullNetworkPort networkPort = new NullNetworkPort();
    private final NullFilePort filePort = new NullFilePort();

    public void init()
    {
        LOG.log(Level.INFO, "Initializing null HAL adapter (headless / testing)");
        timePort.init();
        inputPort.init();
        networkPort.init();
    }

    public void shutdown()
    {
        LOG.log(Level.INFO, "Shutting down null HAL adapter");
        timePort.shutdown();
        inputPort.shutdown();
        networkPort.shutdown();
    }

    public I_TimePort getTimePort()
    {
        return timePort;
    }

    public I_InputPort getInputPort()
    {
        return inputPort;
    }

    public I_NetworkPort getNetworkPort()
    {
        return networkPort;
    }

    public I_FilePort getFilePort()
    {
        return filePort;
    }
}
