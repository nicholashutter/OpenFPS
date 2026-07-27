/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.audio.adapter.NullAudioPort;
import com.openfps.engine.common.Constants;
import com.openfps.engine.common.UserProfile;
import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.core.subsystem.impl.AudioSubsystem;
import com.openfps.engine.core.subsystem.impl.CoreSubsystem;
import com.openfps.engine.core.subsystem.impl.GameplaySubsystem;
import com.openfps.engine.core.subsystem.impl.HalSubsystem;
import com.openfps.engine.core.subsystem.impl.MemorySubsystem;
import com.openfps.engine.core.subsystem.impl.NetSubsystem;
import com.openfps.engine.core.subsystem.impl.RenderSubsystem;
import com.openfps.engine.gameplay.adapter.NullGameplayPort;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;
import com.openfps.engine.hal.adapter.sqlite.SqliteAdapterFactory;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
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
 *   2. HAL adapters — either NullAdapterFactory (in-memory) or
 *      SqliteAdapterFactory (real on-disk profile persistence)
 *   3. Read core count from I_SystemInfoPort
 *   4. Load or create user profile
 *   5. Event bus (single shared queue, blocking backpressure)
 *   6. Subsystem registry — register all subsystems
 *   7. Worker pool — N = cores/2 hot threads
 *   8. Start the pool
 *   9. Start GameLoop on its own thread (event producer)
 *  10. Wait for the loop to finish
 *  11. Drain the bus, stop the pool, save profile, shut down subsystems
 *  12. Shut down HAL and memory port
 *
 * CLI args:
 *   --fps=N     Rate: 30, 60, or 120 (default 60)
 *   --no-sqlite Use in-memory profile instead of SQLite
 *   --headless  Use null adapter factory (no real ports)
 */
public final class EngineMain
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineMain.class);

    /** Default event-bus capacity. */
    private static final int DEFAULT_BUS_CAPACITY = 1024;

    /**
     * Boots the engine.
     */
    public static void main(final String[] args)
    {
        LOG.info("OpenFPS engine booting...");
        final FrameRate rate;
        final boolean useSqlite;
        final boolean headless;
        try
        {
            rate = parseFpsArg(args);
            useSqlite = !hasFlag(args, "--no-sqlite");
            headless = hasFlag(args, "--headless");
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("Failed to parse arguments: {}", e.getMessage());
            System.err.println("ERROR: " + e.getMessage());
            System.err.println("Usage: java -jar openfps.jar [--fps=30|60|120] [--no-sqlite] [--headless]");
            System.exit(1);
            return;
        }
        LOG.info("Engine version 0.1.0-SNAPSHOT, target rate={} Hz, java={}, sqlite={}, headless={}",
            rate.fps(), System.getProperty("java.version"), useSqlite, headless);
        new EngineMain().run(GameConfig.headless(rate), useSqlite, headless);
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

    /** Returns true if {@code args} contains the given flag. */
    private static boolean hasFlag(final String[] args, final String flag)
    {
        if (args == null) return false;
        for (final String a : args)
        {
            if (flag.equals(a)) return true;
        }
        return false;
    }

    /**
     * Runs the engine with the requested HAL factory and a bounded tic
     * count.
     *
     * @param config    the game config (rate + maxTics)
     * @param useSqlite if true, use the SqliteAdapterFactory (real
     *                  on-disk profile persistence). If false, use the
     *                  NullAdapterFactory (in-memory profile).
     * @param headless  if true, force the null adapter (overrides
     *                  useSqlite). Currently both paths use null ports
     *                  for time / input / network; the difference is
     *                  the user profile backend.
     */
    public void run(final GameConfig config, final boolean useSqlite, final boolean headless)
    {
        // -- 1. Memory port
        final I_MemoryPort memory = MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE);
        memory.init(Constants.ZONE_HEAP_SIZE);

        // -- 2. HAL adapters
        // Both factories use null ports for time / input / network / file
        // / system info; they differ only on the user profile backend.
        // This is a stepping stone to the real desktop factory (Phase 1.5).
        final I_TimePort timePort;
        final I_InputPort inputPort;
        final I_SystemInfoPort sysinfo;
        final I_UserProfilePort userProfile;
        final AutoCloseable halCloser;

        if (useSqlite && !headless)
        {
            final SqliteAdapterFactory sqlite = new SqliteAdapterFactory();
            sqlite.init();
            timePort  = sqlite.getTimePort();
            inputPort = sqlite.getInputPort();
            sysinfo   = sqlite.getSystemInfoPort();
            userProfile = sqlite.getUserProfilePort();
            halCloser = sqlite::shutdown;
        }
        else
        {
            final NullAdapterFactory nullFactory = new NullAdapterFactory();
            nullFactory.init();
            timePort  = nullFactory.getTimePort();
            inputPort = nullFactory.getInputPort();
            sysinfo   = nullFactory.getSystemInfoPort();
            userProfile = nullFactory.getUserProfilePort();
            halCloser = nullFactory::shutdown;
        }

        // -- 3. Worker count from HAL
        final int logicalCores = sysinfo.logicalProcessorCount();
        final int workerCount = ThreadPoolFactory.recommendedWorkerCount(logicalCores);
        LOG.info("System: {} logical cores, {} workers, target rate={} Hz",
            logicalCores, workerCount, config.rate().fps());

        // -- 4. Load or create user profile
        final UserProfile profile = loadOrCreateProfile(userProfile, timePort);

        // -- 5. Event bus
        final I_EventBusPort bus = EventBusFactory.createShared();
        bus.init(DEFAULT_BUS_CAPACITY);

        // -- 6. Subsystem registry
        final SubsystemRegistry subsystems = new SubsystemRegistry();
        subsystems.register(new CoreSubsystem());
        subsystems.register(new MemorySubsystem(memory));
        subsystems.register(new HalSubsystem(inputPort));
        subsystems.register(new NetSubsystem(new NullNetworkPort()));
        subsystems.register(new GameplaySubsystem(new NullGameplayPort()));
        subsystems.register(new RenderSubsystem(new NullRenderPort()));
        subsystems.register(new AudioSubsystem(new NullAudioPort()));
        subsystems.initAll();

        // -- 7. Worker pool
        final I_ThreadPoolPort pool = ThreadPoolFactory.createFixed(bus, subsystems);
        pool.init(workerCount);
        pool.start();

        // -- 8. Event factory + GameLoop (producer)
        // The loop is the sole event producer and cannot run on the worker
        // pool — it would occupy a consumer thread for the whole run and
        // deadlock at workerCount == 1. It runs on this thread instead;
        // the workers are already hot and draining the bus in parallel.
        final EventFactory eventFactory = new EventFactory(timePort);
        final GameLoop loop = new GameLoop(timePort, bus, eventFactory, config);
        loop.run();

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

        // -- 10. Save profile with updated playtime + last-login
        saveProfile(userProfile, profile, timePort);

        try
        {
            halCloser.close();
        }
        catch (final Exception e)
        {
            LOG.warn("HAL adapter close threw", e);
        }
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

    /**
     * Loads the most recent user profile, or creates a new one if the
     * database is empty.
     */
    private static UserProfile loadOrCreateProfile(final I_UserProfilePort port,
                                                   final I_TimePort timePort)
    {
        final var existing = port.findAll();
        if (existing.isEmpty())
        {
            final UserProfile fresh = UserProfile.newDefault(timePort.epochMillis());
            port.save(fresh);
            LOG.info("Created new user profile: id={}, name='{}'",
                fresh.id(), fresh.displayName());
            return fresh;
        }
        UserProfile mostRecent = existing.get(0);
        for (final UserProfile p : existing)
        {
            if (p.lastLoginAtEpochMs() > mostRecent.lastLoginAtEpochMs())
            {
                mostRecent = p;
            }
        }
        final UserProfile touched = mostRecent.withLastLogin(timePort.epochMillis());
        port.save(touched);
        LOG.info("Loaded user profile: id={}, name='{}'",
            touched.id(), touched.displayName());
        return touched;
    }

    /**
     * Saves the profile with the current playtime and last-login
     * timestamp.
     */
    private static void saveProfile(final I_UserProfilePort port, final UserProfile profile,
                                    final I_TimePort timePort)
    {
        final long now = timePort.epochMillis();
        // Wall clock can jump backwards; clamp so playtime never regresses.
        final long additionalSeconds = Math.max(0L, (now - profile.lastLoginAtEpochMs()) / 1000L);
        final UserProfile updated = profile
            .withAddedPlaytime(additionalSeconds)
            .withLastLogin(now)
            .withUpdatedAt(now);
        port.save(updated);
        LOG.info("Saved user profile: id={}, playtime={}s",
            updated.id(), updated.totalPlaytimeSeconds());
    }
}
