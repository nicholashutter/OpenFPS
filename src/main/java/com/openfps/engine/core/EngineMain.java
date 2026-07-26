/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.common.Constants;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * D_ Main entry point for the OpenFPS engine.
 * Initializes the hardware abstraction layer, constructs the game loop,
 * and drives it until shutdown.
 */
public final class EngineMain
{
    private static final Logger LOG = Logger.getLogger(EngineMain.class.getName());

    /**
     * Boots the engine with the null (headless) HAL adapter.
     * Production launch replaces the adapter factory call with a
     * platform-detection loader.
     */
    public static void main(final String[] args)
    {
        LOG.log(Level.INFO, "OpenFPS engine booting...");

        // TODO: Replace with platform-detection loader (Phase 1)
        final NullAdapterFactory adapters = new NullAdapterFactory();
        adapters.init();

        final GameLoop loop = new GameLoop(
            adapters.getTimePort(),
            adapters.getInputPort(),
            adapters.getNetworkPort()
        );

        try
        {
            loop.run();
        }
        finally
        {
            adapters.shutdown();
            LOG.log(Level.INFO, "OpenFPS engine shut down cleanly.");
        }
    }
}
