/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.common.Constants;
import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.core.subsystem.impl.AudioSubsystem;
import com.openfps.engine.core.subsystem.impl.GameplaySubsystem;
import com.openfps.engine.core.subsystem.impl.HalSubsystem;
import com.openfps.engine.core.subsystem.impl.MemorySubsystem;
import com.openfps.engine.core.subsystem.impl.NetSubsystem;
import com.openfps.engine.core.subsystem.impl.RenderSubsystem;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.memory.factory.MemoryPortFactory;
import com.openfps.engine.memory.port.I_MemoryPort;
import com.openfps.engine.net.adapter.NullNetworkPort;
import com.openfps.engine.render.adapter.NullRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D_ Main entry point for the OpenFPS engine.
 *
 * Bootstrap sequence:
 *   1. Memory port (JvmMemoryPort default)
 *   2. HAL adapters (NullAdapterFactory for headless)
 *   3. Read core count from I_SystemInfoPort
 *   4. Event bus (single shared queue, blocking backpressure)
 *   5. Subsystem registry — register all subsystems
 *   6. Worker pool — N = cores/2 hot threads
 *   7. Start the pool
 *   8. Start GameLoop on its own thread (event producer)
 *   9. Wait for SHUTDOWN event
 *  10. Drain the bus, stop the pool, shut down subsystems
 *  11. Shut down HAL and memory port
 */
public final class EngineMain
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineMain.class);

    /** Default event-bus capacity. */
    private static final int DEFAULT_BUS_CAPACITY = 1024;

    /** Default max tics for the headless run (avoids running forever in CI). */
    private static final int DEFAULT_MAX_TICS = 70;  // ~2 seconds at 35 Hz

    /**
     * Boots the engine. Replaces the previous main with a full event-
     * driven bootstrap.
     *
     * @param args CLI args (none currently consumed)
     */
    public static void main(final String[] args)
    {
        LOG.info("OpenFPS engine booting...");
        new EngineMain().runHeadless();
    }

    /**
     * Runs the engine with the null HAL adapter and a bounded tic count
     * — for development, CI, and smoke tests. Production launch wires
     * a platform-specific adapter factory.
     */
    public void runHeadless()
    {
        // -- 1. Memory port
        final I_MemoryPort memory = MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE);
        memory.init(Constants.ZONE_HEAP_SIZE);

        // -- 2. HAL adapters
        final NullAdapterFactory hal = new NullAdapterFactory();
        hal.init();
        final I_SystemInfoPort sysinfo = hal.getSystemInfoPort();

        // -- 3. Worker count from HAL
        final int logicalCores = sysinfo.logicalProcessorCount();
        final int workerCount = Math.max(1, logicalCores / 2);
        LOG.info("System: {} logical cores, {} workers, java={}",
            logicalCores, workerCount, sysinfo.javaVersion());

        // -- 4. Event bus
        final I_EventBusPort bus = EventBusFactory.createShared();
        bus.init(DEFAULT_BUS_CAPACITY);

        // -- 5. Subsystem registry
        final SubsystemRegistry subsystems = new SubsystemRegistry();
        subsystems.register(new MemorySubsystem(memory));
        subsystems.register(new HalSubsystem(hal.getInputPort()));
        subsystems.register(new NetSubsystem(new NullNetworkPort()));
        subsystems.register(new GameplaySubsystem(new NullGameplayPort()));
        subsystems.register(new RenderSubsystem(new NullRenderPort()));
        subsystems.register(new AudioSubsystem(new NullAudioPort()));
        subsystems.initAll();

        // -- 6. Worker pool
        final I_ThreadPoolPort pool = ThreadPoolFactory.createFixed(bus, subsystems);
        pool.init(workerCount);
        pool.start();

        // -- 7. Event factory + GameLoop (producer)
        final EventFactory eventFactory = new EventFactory(timeOriginNanos());
        final GameLoop loop = new GameLoop(hal.getTimePort(), bus, eventFactory, DEFAULT_MAX_TICS);
        final Thread loopThread = new Thread(loop, "GameLoop");
        loopThread.setDaemon(true);
        loopThread.start();

        try
        {
            // -- 8. Wait for the loop to finish (it self-terminates after maxTics)
            loopThread.join();
        }
        catch (final InterruptedException e)
        {
            LOG.info("Main thread interrupted — shutting down");
            Thread.currentThread().interrupt();
        }

        // -- 9. Drain and stop
        try
        {
            pool.shutdown();
            pool.awaitTermination(2000);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        subsystems.shutdownAll();
        bus.shutdown();
        hal.shutdown();
        try
        {
            memory.shutdown();
        }
        catch (final RuntimeException ignored)
        {
            // already shut down — fine
        }

        LOG.info("OpenFPS engine shut down cleanly.");
    }

    /** Returns a reference origin for timestamps. */
    private static long timeOriginNanos()
    {
        return System.nanoTime();
    }
}
