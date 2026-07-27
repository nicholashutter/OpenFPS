/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.sqlite;

import com.openfps.engine.hal.adapter.nulladapter.NullFilePort;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullNetworkPort;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_FilePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production-ish HAL adapter factory — uses Xerial SQLite for user
 * profile persistence and null adapters for everything else.
 *
 * This is a stepping stone to the real desktop factory (Phase 1.4+).
 * For now it provides real on-disk persistence for the user profile
 * while keeping the rest of the ports headless. The engine code is
 * identical to the null-factory case — the only difference is the
 * profile port implementation.
 *
 * Future: the real desktop factory will swap in LWJGL3-based
 * implementations for the time, input, and render ports.
 */
public final class SqliteAdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(SqliteAdapterFactory.class);

    private final NullTimePort timePort = new NullTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final NullNetworkPort networkPort = new NullNetworkPort();
    private final NullFilePort filePort = new NullFilePort();
    private final NullSystemInfoPort systemInfo = new NullSystemInfoPort();
    private final SqliteUserProfilePort userProfile = new SqliteUserProfilePort();

    public void init()
    {
        LOG.info("Initializing sqlite HAL adapter (Xerial SQLite for user profile)");
        timePort.init();
        inputPort.init();
        networkPort.init();
        systemInfo.init();
        userProfile.init();
    }

    public void shutdown()
    {
        LOG.info("Shutting down sqlite HAL adapter");
        userProfile.shutdown();
        systemInfo.shutdown();
        networkPort.shutdown();
        inputPort.shutdown();
        timePort.shutdown();
    }

    public I_TimePort getTimePort()              { return timePort; }
    public I_InputPort getInputPort()            { return inputPort; }
    public I_NetworkPort getNetworkPort()        { return networkPort; }
    public I_FilePort getFilePort()              { return filePort; }
    public I_SystemInfoPort getSystemInfoPort()  { return systemInfo; }
    public I_UserProfilePort getUserProfilePort(){ return userProfile; }
}
