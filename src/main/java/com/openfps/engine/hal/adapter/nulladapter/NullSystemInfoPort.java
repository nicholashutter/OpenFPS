/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_SystemInfoPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Null implementation of I_SystemInfoPort.
 * Uses {@link Runtime} for all queries — no OS calls.
 *
 * Note: {@code Runtime.availableProcessors()} returns LOGICAL cores
 * (including hyperthreaded). We use that value for {@code physicalProcessorCount}
 * too as a best-effort; the engine only requires "consistent with the OS."
 */
public final class NullSystemInfoPort implements I_SystemInfoPort
{
    private static final Logger LOG = LoggerFactory.getLogger(NullSystemInfoPort.class);

    private volatile State state;

    public NullSystemInfoPort()
    {
        this.state = State.UNINITIALIZED;
    }

    @Override
    public int logicalProcessorCount()
    {
        return Runtime.getRuntime().availableProcessors();
    }

    @Override
    public int physicalProcessorCount()
    {
        // Null adapter: best-effort — same as logical. The desktop adapter
        // (Phase 2) can use oshi or WMI/sysctl for the true physical count.
        return Runtime.getRuntime().availableProcessors();
    }

    @Override
    public long totalMemoryBytes()
    {
        return Runtime.getRuntime().maxMemory();
    }

    @Override
    public long freeMemoryBytes()
    {
        return Runtime.getRuntime().freeMemory();
    }

    @Override
    public String osName()
    {
        return System.getProperty("os.name", "unknown");
    }

    @Override
    public String osVersion()
    {
        return System.getProperty("os.version", "unknown");
    }

    @Override
    public String javaVersion()
    {
        return System.getProperty("java.version", "unknown");
    }

    @Override
    public void init()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("init() called from state SHUTDOWN");
        }
        state = State.READY;
        LOG.info("NullSystemInfoPort initialized: cores={}, os={}, java={}",
            logicalProcessorCount(), osName(), javaVersion());
    }

    @Override
    public void shutdown()
    {
        state = State.SHUTDOWN;
    }

    @Override
    public State state()
    {
        return state;
    }
}
