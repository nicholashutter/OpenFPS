/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.port.I_DatagramPort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless adapter factory for unit testing and CI.
 * All adapters are null / in-memory — no OS or graphics calls.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class NullAdapterFactory implements I_AdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(NullAdapterFactory.class);

    private final NullTimePort timePort = new NullTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final NullDatagramPort datagramPort = new NullDatagramPort();
    private final NullFilePort filePort = new NullFilePort();
    private final NullSystemInfoPort systemInfo = new NullSystemInfoPort();
    private final MemoryUserProfilePort userProfile = new MemoryUserProfilePort();
    private final NullWindowPort windowPort = new NullWindowPort();

    @Override
    public void init()
    {
        LOG.info("Initializing null HAL adapter (headless / testing)");
        timePort.init();
        inputPort.init();
        datagramPort.init();
        systemInfo.init();
        userProfile.init();
        windowPort.init();
    }

    @Override
    public void shutdown()
    {
        LOG.info("Shutting down null HAL adapter");
        windowPort.shutdown();
        timePort.shutdown();
        inputPort.shutdown();
        datagramPort.shutdown();
        systemInfo.shutdown();
        userProfile.shutdown();
    }

    @Override
    public I_TimePort getTimePort()
    {
        return timePort;
    }

    @Override
    public I_InputPort getInputPort()
    {
        return inputPort;
    }

    @Override
    public I_DatagramPort getDatagramPort()
    {
        return datagramPort;
    }

    @Override
    public I_FilePort getFilePort()
    {
        return filePort;
    }

    @Override
    public I_SystemInfoPort getSystemInfoPort()
    {
        return systemInfo;
    }

    @Override
    public I_UserProfilePort getUserProfilePort()
    {
        return userProfile;
    }

    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }
}
