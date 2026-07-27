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
import com.openfps.engine.hal.adapter.AdapterFactorySelector;
import com.openfps.engine.hal.adapter.HalBackend;
import com.openfps.engine.hal.adapter.I_AdapterFactory;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_SystemInfoPort;
import com.openfps.engine.hal.port.I_TimePort;
import com.openfps.engine.hal.port.I_UserProfilePort;
import com.openfps.engine.hal.port.I_WindowPort;
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

    /** How long the windowed path waits for the game loop to stop, in ms. */
    private static final long LOOP_JOIN_TIMEOUT_MS = 2000L;

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

    /**
     * Parses the {@code --fps=N} argument. Defaults to 60 if not present.
     *
     * Public so the platform launchers ({@code :desktop}, {@code :android})
     * can reuse it instead of each growing its own copy of the same parse —
     * STYLE.md § 13.
     *
     * @param args the CLI arguments, may be null
     * @return the requested frame rate
     */
    public static FrameRate parseFpsArg(final String[] args)
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
        run(config, AdapterFactorySelector.create(selectBackend(useSqlite, headless)));
    }

    /**
     * Runs the engine against a caller-supplied HAL factory.
     *
     * This is the injection point for platform launchers. {@code :engine}
     * must not depend on {@code :desktop} or {@code :android} — that would
     * be a module cycle and would drag a windowing toolkit into the module
     * CI builds headlessly — so {@link AdapterFactorySelector} can never
     * name a platform factory that lives outside this module. Inverting it
     * costs one parameter: the launcher constructs its own
     * {@link I_AdapterFactory} and hands it in, and every other bootstrap
     * decision stays here where it belongs.
     *
     * The factory is taken uninitialized; this method owns its
     * {@code init()} and {@code shutdown()}, on the calling thread, which
     * must be the main thread when the factory yields a real window.
     *
     * @param config the game config (rate + maxTics)
     * @param hal    the uninitialized HAL factory to boot against
     */
    public void run(final GameConfig config, final I_AdapterFactory hal)
    {
        if (hal == null)
        {
            throw new IllegalArgumentException("hal must not be null");
        }

        // -- 1. Memory port
        final I_MemoryPort memory = MemoryPortFactory.createJvm(Constants.ZONE_HEAP_SIZE);
        memory.init(Constants.ZONE_HEAP_SIZE);

        // -- 2. HAL adapters
        // One factory owns every port for the chosen backend. init() and
        // shutdown() are main-thread only — see I_AdapterFactory.
        hal.init();
        final I_TimePort timePort = hal.getTimePort();
        final I_InputPort inputPort = hal.getInputPort();
        final I_SystemInfoPort sysinfo = hal.getSystemInfoPort();
        final I_UserProfilePort userProfile = hal.getUserProfilePort();
        final I_WindowPort window = hal.getWindowPort();

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

        // -- 8. Event factory + GameLoop (producer) on its own thread
        //
        // The loop gets a dedicated thread because both other candidates
        // are ruled out:
        //   - NOT a worker-pool thread: it would occupy a consumer for the
        //     whole run and deadlock outright at workerCount == 1.
        //   - NOT the main thread: GLFW requires window creation and
        //     glfwPollEvents() on the main thread, so main is reserved for
        //     the platform pump below.
        final EventFactory eventFactory = new EventFactory(timePort);
        final GameLoop loop = new GameLoop(timePort, bus, eventFactory, config);
        final Thread loopThread = new Thread(loop, "openfps-gameloop");
        loopThread.start();

        // -- 9. Main thread: platform event pump (windowed) or just wait
        runPlatformPump(window, loop, loopThread);

        // -- 10. Drain and stop
        // Order matters: the loop must be fully stopped before the pool
        // drains the bus. SharedEventBus.publish() throws once the bus
        // leaves READY, so a still-running producer would crash here.
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

    /**
     * Runs the main thread until the game loop finishes.
     *
     * With a real window this is the GLFW event pump: drain OS events,
     * present a frame, repeat. GLFW requires both on the main thread,
     * which is exactly why the game loop was given its own.
     *
     * Headless there is nothing to pump, so we simply join — no busy-wait
     * on a no-op window, and the behaviour is identical to before the
     * window port existed.
     *
     * @param window the window port (real or null)
     * @param loop the game loop, for requesting stop
     * @param loopThread the thread the loop is running on
     */
    private static void runPlatformPump(final I_WindowPort window,
                                        final GameLoop loop,
                                        final Thread loopThread)
    {
        if (!window.isRealWindow())
        {
            joinLoop(loopThread, 0L);
            return;
        }

        // The platform owns the loop from here. This blocks until the user
        // closes the window (desktop) or the Activity is destroyed
        // (Android). The callback draws; it never advances the simulation —
        // GameLoop is still doing that on its own thread at a fixed rate.
        window.runFrameLoop(new EngineFrameCallback(loopThread, window));

        // Either the loop ended on its own or the user closed the window.
        // shutdown() is idempotent, so calling it in both cases is fine.
        loop.shutdown();
        joinLoop(loopThread, LOOP_JOIN_TIMEOUT_MS);
    }

    /**
     * Waits for the game loop thread to exit.
     *
     * A bounded wait is used for the windowed path because
     * {@code SharedEventBus.publish()} blocks on a full queue — an
     * unbounded join could hang forever if the workers ever wedged.
     *
     * @param loopThread the thread to join
     * @param timeoutMs milliseconds to wait, or 0 to wait indefinitely
     */
    private static void joinLoop(final Thread loopThread, final long timeoutMs)
    {
        try
        {
            loopThread.join(timeoutMs);
            if (loopThread.isAlive())
            {
                LOG.warn("GameLoop did not stop within {} ms — continuing shutdown", timeoutMs);
            }
        }
        catch (final InterruptedException e)
        {
            LOG.info("Main thread interrupted — shutting down");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Picks the HAL backend from the CLI flags.
     *
     * {@code --headless} wins over everything: it forces the fully null
     * backend regardless of the profile choice.
     */
    private static HalBackend selectBackend(final boolean useSqlite, final boolean headless)
    {
        if (headless)
        {
            return HalBackend.NULL;
        }
        if (useSqlite)
        {
            return HalBackend.SQLITE;
        }
        return HalBackend.NULL;
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
