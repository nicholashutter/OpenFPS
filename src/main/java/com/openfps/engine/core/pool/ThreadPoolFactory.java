/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

/**
 * System-level factory for the worker thread pool. The engine never
 * instantiates a pool directly; it asks the factory.
 */
public final class ThreadPoolFactory
{
    private ThreadPoolFactory()
    {
    }

    /**
     * Creates a fixed-size worker pool tied to the given bus and
     * subsystem registry.
     *
     * @param bus the event bus the workers consume from
     * @param registry the subsystem registry workers dispatch to
     * @return a fresh, uninitialized pool (call init() then start())
     */
    public static I_ThreadPoolPort createFixed(final I_EventBusPort bus,
                                                final SubsystemRegistry registry)
    {
        return new WorkerPool(bus, registry);
    }

    /**
     * Calculates the recommended worker count: half the logical core
     * count, with a minimum of 1. The engine reads this from the HAL
     * SystemInfo port.
     *
     * @param logicalCoreCount number of logical CPU cores
     * @return recommended worker count
     */
    public static int recommendedWorkerCount(final int logicalCoreCount)
    {
        final int half = Math.max(1, logicalCoreCount / 2);
        return half;
    }
}
