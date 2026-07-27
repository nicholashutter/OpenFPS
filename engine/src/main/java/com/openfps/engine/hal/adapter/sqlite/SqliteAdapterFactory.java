/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.sqlite;

import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.adapter.nulladapter.NullDatagramPort;
import com.openfps.engine.hal.adapter.nulladapter.NullFilePort;
import com.openfps.engine.hal.adapter.nulladapter.NullInputPort;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;
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
 * Production-ish HAL adapter factory — uses Xerial SQLite for user
 * profile persistence and null adapters for everything else.
 *
 * This is a stepping stone to the real desktop factory (Phase 1.5).
 * For now it provides real on-disk persistence for the user profile
 * while keeping the rest of the ports headless. The engine code is
 * identical to the null-factory case — the only difference is the
 * profile port implementation.
 *
 * Future: the real desktop factory will swap in LWJGL3-based
 * implementations for the time, input, and render ports.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class SqliteAdapterFactory implements I_AdapterFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(SqliteAdapterFactory.class);

    private final NullTimePort timePort = new NullTimePort();
    private final NullInputPort inputPort = new NullInputPort();
    private final NullDatagramPort datagramPort = new NullDatagramPort();
    private final NullFilePort filePort = new NullFilePort();
    private final NullSystemInfoPort systemInfo = new NullSystemInfoPort();
    private final SqliteUserProfilePort userProfile = new SqliteUserProfilePort();
    private final NullWindowPort windowPort = new NullWindowPort();

    @Override
    public void init()
    {
        LOG.info("Initializing sqlite HAL adapter (Xerial SQLite for user profile)");
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
        LOG.info("Shutting down sqlite HAL adapter");
        windowPort.shutdown();
        userProfile.shutdown();
        systemInfo.shutdown();
        datagramPort.shutdown();
        inputPort.shutdown();
        timePort.shutdown();
    }

    /** Returns the time port (null adapter — real one lands in Phase 1.5). */
    @Override
    public I_TimePort getTimePort()
    {
        return timePort;
    }

    /** Returns the input port (null adapter — real one lands in Phase 1.5). */
    @Override
    public I_InputPort getInputPort()
    {
        return inputPort;
    }

    /** Returns the UDP datagram port (null adapter — real one lands in Phase 3). */
    @Override
    public I_DatagramPort getDatagramPort()
    {
        return datagramPort;
    }

    /** Returns the file port (null adapter, backed by real filesystem reads). */
    @Override
    public I_FilePort getFilePort()
    {
        return filePort;
    }

    /** Returns the system info port (null adapter, backed by {@code Runtime}). */
    @Override
    public I_SystemInfoPort getSystemInfoPort()
    {
        return systemInfo;
    }

    /** Returns the SQLite-backed user profile port — the real one. */
    @Override
    public I_UserProfilePort getUserProfilePort()
    {
        return userProfile;
    }

    /** Returns the window port (null — the SQLite backend is headless). */
    @Override
    public I_WindowPort getWindowPort()
    {
        return windowPort;
    }
}
