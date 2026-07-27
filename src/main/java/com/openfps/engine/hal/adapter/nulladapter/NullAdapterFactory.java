/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless adapter factory for unit testing and CI.
 * All adapters are null / in-memory — no OS or graphics calls.
 */
public final class NullAdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(NullAdapterFactory.class);

    private final NullTimePort timePort = new NullTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final NullNetworkPort networkPort = new NullNetworkPort();
    private final NullFilePort filePort = new NullFilePort();
    private final NullSystemInfoPort systemInfo = new NullSystemInfoPort();
    private final MemoryUserProfilePort userProfile = new MemoryUserProfilePort();

    public void init()
    {
        LOG.info("Initializing null HAL adapter (headless / testing)");
        timePort.init();
        inputPort.init();
        networkPort.init();
        systemInfo.init();
        userProfile.init();
    }

    public void shutdown()
    {
        LOG.info("Shutting down null HAL adapter");
        timePort.shutdown();
        inputPort.shutdown();
        networkPort.shutdown();
        systemInfo.shutdown();
        userProfile.shutdown();
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

    public I_SystemInfoPort getSystemInfoPort()
    {
        return systemInfo;
    }

    public I_UserProfilePort getUserProfilePort()
    {
        return userProfile;
    }
}
