/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.common.Constants;
import com.openfps.engine.core.event.EventFactory;
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
 *   9. Wait for the loop to finish (it self-terminates after maxTics)
 *  10. Drain the bus, stop the pool, shut down subsystems
 *  11. Shut down HAL and memory port
 *
 * CLI args:
 *   --fps=N   Frame rate: 30, 60, or 120. Default 60. Anything else is
 *             rejected with a friendly error message.
 */
public final class EngineMain
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineMain.class);

    /** Default event-bus capacity. */
    private static final int DEFAULT_BUS_CAPACITY = 1024;

    /**
     * Boots the engine. CLI args:
     *   --fps=N  Rate: 30, 60, or 120 (default 60)
     */
    public static void main(final String[] args)
    {
        LOG.info("OpenFPS engine booting...");
        final FrameRate rate;
        try
        {
            rate = parseFpsArg(args);
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("Failed to parse --fps argument: {}", e.getMessage());
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Usage: java -jar openfps.jar [--fps=30|60|120]");
            System.exit(1);
            return;
        }
        LOG.info("Engine version 0.1.0-SNAPSHOT, target rate={} Hz, java={}",
            rate.fps(), System.getProperty("java.version"));
        new EngineMain().runHeadless(GameConfig.headless(rate));
    }

    /** Parses the --fps=N argument. Defaults to 60 if not present. */
    static FrameRate parseFpsArg(final String[] args)
    {
        if (args == null)
        {
            return FrameRate.FPS_60;
        }
        for (final String arg : args)
        {
            if (arg == null)
            {
                continue;
            }
            if (arg.startsWith("--fps="))
            {
                return FrameRate.fromString(arg.substring("--fps=".length()));
            }
        }
        return FrameRate.FPS_60;
    }

    /**
     * Runs the engine with the null HAL adapter and a bounded tic count
     * — for development, CI, and smoke tests. Production launch wires
     * a platform-specific adapter factory.
     */
    public void runHeadless(final GameConfig config)
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
        LOG.info("System: {} logical cores, {} workers, target rate={} Hz",
            logicalCores, workerCount, config.rate().fps());

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
        final GameLoop loop = new GameLoop(hal.getTimePort(), bus, eventFactory, config);
        final Thread loopThread = new Thread(loop, "GameLoop");
        loopThread.setDaemon(true);
        loopThread.start();

        try
        {
            loopThread.join();
        }
        catch (final InterruptedException e)
        {
            LOG.info("Main thread interrupted — shutting down");
            Thread.currentThread().interrupt();
        }

        // -- 8. Drain and stop
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
