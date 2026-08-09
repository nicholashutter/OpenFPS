/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

/**
 * A standalone {@code WorkerPool} for a build-time tool, or none at all.
 *
 * Build-time only — this class is never on a runtime classpath.
 *
 * <p>The pool is the engine's own, via {@link ThreadPoolFactory} —
 * {@code AGENTS.md} rule 1 — which means it needs an event bus and a subsystem
 * registry even though a rendering tool publishes nothing to either. They exist
 * solely so {@code submitParallel} has somewhere to live.</p>
 *
 * <p>{@code threads <= 0} yields a {@link #port()} of null, which
 * {@code SoftwareRenderPort} treats as "run every pass on the calling thread".
 * That serial path is the reference the parallel result is compared against,
 * so it is a supported mode rather than a degenerate one.</p>
 */
public final class ToolPool
{
    /** Bus capacity for the standalone pool; nothing is ever published to it. */
    private static final int BUS_CAPACITY = 64;

    /** The bus the pool drains, or null in the serial case. */
    private final I_EventBusPort bus;

    /** The pool, or null in the serial case. */
    private final I_ThreadPoolPort pool;

    private ToolPool(final I_EventBusPort eventBus, final I_ThreadPoolPort threadPool)
    {
        this.bus = eventBus;

        this.pool = threadPool;
    }

    /**
     * Starts a pool, or returns the serial no-pool case.
     *
     * @param threads worker threads to start; zero or fewer means run serially
     * @return an open pool holder; always call {@link #close()}
     */
    public static ToolPool open(final int threads)
    {
        if (threads <= 0)
        {
            return new ToolPool(null, null);
        }

        final I_EventBusPort eventBus = EventBusFactory.createShared();

        eventBus.init(BUS_CAPACITY);

        final I_ThreadPoolPort threadPool =
            ThreadPoolFactory.createFixed(eventBus, new SubsystemRegistry());

        threadPool.init(threads);

        threadPool.start();

        return new ToolPool(eventBus, threadPool);
    }

    /**
     * Returns the pool to hand the renderer, or null for the serial path.
     *
     * @return the thread pool port, or null
     */
    public I_ThreadPoolPort port()
    {
        return pool;
    }

    /**
     * Returns how many worker threads are running, or zero when serial.
     *
     * @return the worker count
     */
    public int workerCount()
    {
        if (pool == null)
        {
            return 0;
        }

        return pool.workerCount();
    }

    /** Stops the pool and the bus. Safe when neither exists. */
    public void close()
    {
        if (pool != null)
        {
            pool.shutdown();
        }

        if (bus != null)
        {
            bus.shutdown();
        }
    }
}
