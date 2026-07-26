/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.memory.factory.MemoryPortFactory;
import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.common.Constants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D_ Main entry point for the OpenFPS engine.
 * Initializes the memory port, the hardware abstraction layer, and the
 * game loop. Shuts everything down on exit.
 */
public final class EngineMain
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineMain.class);

    /**
     * Boots the engine with the JVM memory port and the null (headless)
     * HAL adapter. Production launch replaces these with the appropriate
     * platform-specific factory calls.
     */
    public static void main(final String[] args)
    {
        LOG.info("OpenFPS engine booting...");
        LOG.info("Engine version 0.1.0-SNAPSHOT, Java {}, platform {}",
            System.getProperty("java.version"),
            System.getProperty("os.name"));

        // -- Memory port: JVM backend by default, bounded by 16 MB.
        //    All engine allocation goes through this single port.
        final I_MemoryPort memory = MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE);
        memory.init(Constants.ZONE_HEAP_SIZE);
        LOG.info("Memory port initialized: state={}, capacity={} bytes",
            memory.state(), memory.totalBytes());

        // -- HAL: null (headless) adapter. Replace with platform loader.
        final NullAdapterFactory adapters = new NullAdapterFactory();
        adapters.init();
        final I_TimePort timePort = adapters.getTimePort();
        final I_InputPort inputPort = adapters.getInputPort();
        final I_NetworkPort networkPort = adapters.getNetworkPort();

        // -- Main game loop
        final GameLoop loop = new GameLoop(timePort, inputPort, networkPort);

        try
        {
            loop.run();
        }
        finally
        {
            adapters.shutdown();
            if (memory.state() == I_MemoryPort.State.ACTIVE
                || memory.state() == I_MemoryPort.State.READY)
            {
                memory.shutdown();
            }
            LOG.info("OpenFPS engine shut down cleanly.");
        }
    }
}
