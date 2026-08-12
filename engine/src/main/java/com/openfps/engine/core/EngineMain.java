/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

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
import com.openfps.engine.gameplay.port.I_GameplayPort;
import com.openfps.engine.gameplay.port.I_GameplayPortFactory;
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
import com.openfps.engine.render.port.I_RenderPort;
import com.openfps.engine.render.port.I_RenderPortFactory;

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
 *   7. Worker pool — N = cores - 1 hot threads (see ThreadPoolFactory)
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
     * How long a shutdown waits for the game loop to stop, in ms.
     *
     * Bounded rather than indefinite because {@code SharedEventBus.publish()}
     * blocks on a full queue — an unbounded join would hang forever if the
     * workers ever wedged. Package-private: {@link EngineSession} owns
     * teardown and needs the same bound.
     */
    static final long LOOP_JOIN_TIMEOUT_MS = 2000L;

    /**
     * Boots the engine.
     */
    public static void main(final String[] args)
    {
        // Install the SLF4J-to-log-bus bridge before the first log
        // call, so every line below is also visible to any consumer
        // subscribed to the engine's main bus (a file writer, a debug
        // overlay, anything else). Idempotent: a second install is a
        // no-op, so the launcher can call install() again to be sure
        // the bridge is in place.
        com.openfps.engine.log.LogbackBridgeBootstrap.install();

        com.openfps.engine.log.LogBusFactory.startDrainTask();

        // Install the on-disk log sink after the bridge is in
        // place: SLF4J → bridge → bus → file sink. The install
        // is a no-op when -Dopenfps.log.file=off / OPENFPS_LOG_FILE=off
        // is set, so a developer who does not want a file can
        // turn it off without changing code. The path resolves
        // against the project root by default; see LogSinkPaths.
        com.openfps.engine.log.LogBusFactory.installDefaultFileSink();

        LOG.info("OpenFPS engine booting...");

        final FrameRate rate;

        final boolean useSqlite;

        final boolean headless;

        final String mapId;

        try
        {
            rate = parseFpsArg(args);

            useSqlite = !hasFlag(args, "--no-sqlite");

            headless = hasFlag(args, "--headless");

            mapId = parseMapArg(args);
        }
        catch (final IllegalArgumentException e)
        {
            LOG.error("Failed to parse arguments: {}", e.getMessage());

            System.err.println("ERROR: " + e.getMessage());

            System.err.println("Usage: java -jar openfps.jar [--fps=30|60|120]"
                + " [--no-sqlite] [--headless] [--map=<id>]");

            System.exit(1);

            return;
        }

        LOG.info("Engine version 0.1.0-SNAPSHOT, target rate={} Hz, java={}, sqlite={}, "
            + "headless={}, map={}", rate.fps(), System.getProperty("java.version"), useSqlite,
            headless, displayMapId(mapId));

        new EngineMain().run(GameConfig.headless(rate), useSqlite, headless, mapId);
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

    /**
     * Parses the {@code --map=<id>} argument. Returns null if not present.
     *
     * <p>The id is whatever the caller typed after {@code --map=}; whether
     * it is a real map is a runtime question, answered by
     * {@code MapLibrary.has(id)}. A typo here fails at the first match
     * lookup, not at parse time, because the engine does not have to
     * know which maps exist to parse its own command line.</p>
     *
     * @param args the CLI arguments, may be null
     * @return the map id, or null
     */
    public static String parseMapArg(final String[] args)
    {
        if (args == null)
        {
            return null;
        }

        for (final String arg : args)
        {
            if (arg == null)
            {
                continue;
            }

            if (arg.startsWith("--map="))
            {
                return arg.substring("--map=".length());
            }
        }

        return null;
    }

    /**
     * Returns a printable representation of a map id: the id itself, or
     * the literal {@code <none>} when null.
     *
     * @param mapId the parsed map id, may be null
     * @return a non-null display string
     */
    private static String displayMapId(final String mapId)
    {
        if (mapId == null)
        {
            return "<none>";
        }

        return mapId;
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
        run(config, useSqlite, headless, null);
    }

    /**
     * Runs the engine with the requested HAL factory, a bounded tic count
     * and an optional map id.
     *
     * <p>When {@code mapId} is non-null the engine runs the headless map
     * smoke test against the named map: a {@code MapSmokeGameplayPort}
     * ticks a {@code Match} built from the spec until the round ends or
     * the engine's max-tic cap is reached. A null {@code mapId} keeps the
     * legacy behaviour of an empty null-port run.</p>
     *
     * <p>The map id is taken on trust by the engine and resolved through
     * {@code MapLibrary}; an unknown id falls back to {@code cornerstone}
     * at the gameplay-port layer and is logged at {@code WARN}, so a
     * typo in {@code --map=} does not stop the engine from booting.</p>
     *
     * @param config    the game config (rate + maxTics)
     * @param useSqlite if true, use the on-disk profile; false for the
     *                  in-memory profile
     * @param headless  if true, force the null adapter (overrides
     *                  useSqlite)
     * @param mapId     the id of the map to load, or null for the legacy
     *                  no-map path
     */
    public void run(final GameConfig config, final boolean useSqlite, final boolean headless,
        final String mapId)
    {
        run(config, AdapterFactorySelector.create(selectBackend(useSqlite, headless)),
            EngineMain::nullRenderPort, mapSmokeFactory(mapId));
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
        run(config, hal, EngineMain::nullRenderPort);
    }

    /**
     * Runs the engine against a caller-supplied HAL factory and renderer.
     *
     * @param config the game config (rate + maxTics)
     * @param hal    the uninitialized HAL factory to boot against
     * @param renderPortFactory builds the render port once the worker pool and
     *     the clock exist
     */
    public void run(final GameConfig config, final I_AdapterFactory hal,
                    final I_RenderPortFactory renderPortFactory)
    {
        final EngineSession session = start(config, hal, renderPortFactory);

        session.awaitPlatformLoop();

        session.stop();
    }

    /**
     * Runs the engine against a caller-supplied HAL factory, renderer and
     * gameplay port factory. The full injection point a launcher needs to
     * build both subsystems with their ports.
     *
     * @param config               the game config (rate + maxTics)
     * @param hal                  the uninitialized HAL factory
     * @param renderPortFactory    builds the render port
     * @param gameplayPortFactory  builds the gameplay port
     */
    public void run(final GameConfig config, final I_AdapterFactory hal,
                    final I_RenderPortFactory renderPortFactory,
                    final I_GameplayPortFactory gameplayPortFactory)
    {
        final EngineSession session = start(config, hal, renderPortFactory, gameplayPortFactory);

        session.awaitPlatformLoop();

        session.stop();
    }

    /**
     * The default renderer: a null port that draws nothing.
     *
     * A method reference rather than a lambda body so the headless default
     * reads the same as any other {@code I_RenderPortFactory}.
     *
     * @param pool ignored — the null port does no work
     * @param time ignored — the null port measures nothing
     * @return a fresh {@link NullRenderPort}
     */
    private static I_RenderPort nullRenderPort(final I_ThreadPoolPort pool, final I_TimePort time)
    {
        return new NullRenderPort();
    }

    /**
     * Builds the headless gameplay port for a given map id, or the null
     * port when no map is requested.
     *
     * <p>The factory is a method reference rather than a lambda body so
     * the call site reads the same as the other engine-default factories
     * and a future caller can override the map without rewriting the
     * bootstrap.</p>
     *
     * @param mapId the id of the map to load, or null for the legacy
     *              no-map path
     * @return a fresh factory
     */
    private static I_GameplayPortFactory mapSmokeFactory(final String mapId)
    {
        if (mapId == null)
        {
            return EngineMain::nullGameplayPort;
        }

        return inputPort ->
            com.openfps.engine.gameplay.map.MapSmokeGameplayPort.create(inputPort, mapId);
    }

    /**
     * Boots the engine and RETURNS — the caller keeps its thread.
     *
     * This and {@link EngineSession#stop()} are the lifecycle API for every
     * platform. Nothing here blocks, so a caller that must return promptly
     * (Android's {@code onCreate}, which ANRs if held) and a caller that is
     * happy to block (a desktop {@code main}) use the identical pair. The
     * blocking is factored out into
     * {@link EngineSession#awaitPlatformLoop()}, which is a property of the
     * window rather than of the engine — {@link #run} is now just those
     * three calls in a row.
     *
     * The factory is taken uninitialized; the returned session owns its
     * {@code shutdown()}. Call from the main thread when the factory yields
     * a real window.
     *
     * @param config the game config (rate + maxTics)
     * @param hal    the uninitialized HAL factory to boot against
     * @return a live session; call {@link EngineSession#stop()} to tear it down
     */
    private EngineSession start(final GameConfig config, final I_AdapterFactory hal)
    {
        return start(config, hal, EngineMain::nullRenderPort);
    }

    /**
     * Boots the engine with a caller-supplied renderer and RETURNS.
     *
     * <b>Why the renderer arrives as a factory.</b> The software rasterizer
     * needs the worker pool ({@code render/README.md} § 7 makes parallel tile
     * dispatch a correctness property) and {@code I_TimePort}, and both are
     * created here. A launcher therefore cannot construct the port before
     * calling in, and binding the pool afterwards would race the game loop
     * thread that step 8 starts. One small interface removes both problems —
     * see {@link I_RenderPortFactory}.
     *
     * @param config the game config (rate + maxTics)
     * @param hal    the uninitialized HAL factory to boot against
     * @param renderPortFactory builds the render port; must not be null
     * @return a live session; call {@link EngineSession#stop()} to tear it down
     */
    private EngineSession start(final GameConfig config, final I_AdapterFactory hal,
                                final I_RenderPortFactory renderPortFactory)
    {
        return start(config, hal, renderPortFactory, EngineMain::nullGameplayPort);
    }

    /**
     * The default gameplay port: one that does nothing.
     *
     * A method reference rather than a lambda body so the headless default
     * reads the same as any other {@link I_GameplayPortFactory}.
     *
     * @param inputPort ignored — the null port reads no input
     * @return a fresh {@link NullGameplayPort}
     */
    private static I_GameplayPort nullGameplayPort(final I_InputPort inputPort)
    {
        return new NullGameplayPort();
    }

    /**
     * Boots the engine with a caller-supplied renderer and gameplay port.
     *
     * <b>Why the gameplay port also arrives as a factory.</b> Same reason the
     * renderer does: a port that reads input needs {@link I_InputPort}, which
     * does not exist until {@code hal.init()} has run inside this method, and
     * binding it after {@code start} returns would race the game loop thread
     * that step 8 starts. See {@link I_GameplayPortFactory}.
     *
     * <b>Ordering, which callers depend on:</b> the render port is built
     * <em>before</em> the gameplay port, so a launcher may pass the same holder
     * as both factories and read the renderer out of it. Do not reorder these
     * two lines.
     *
     * @param config the game config (rate + maxTics)
     * @param hal    the uninitialized HAL factory to boot against
     * @param renderPortFactory builds the render port; must not be null
     * @param gameplayPortFactory builds the gameplay port; must not be null
     * @return a live session; call {@link EngineSession#stop()} to tear it down
     */
    public EngineSession start(final GameConfig config, final I_AdapterFactory hal,
                               final I_RenderPortFactory renderPortFactory,
                               final I_GameplayPortFactory gameplayPortFactory)
    {
        if (hal == null)
        {
            throw new IllegalArgumentException("hal must not be null");
        }

        if (renderPortFactory == null)
        {
            throw new IllegalArgumentException("renderPortFactory must not be null");
        }

        if (gameplayPortFactory == null)
        {
            throw new IllegalArgumentException("gameplayPortFactory must not be null");
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
        //
        // The HAL is where availableProcessors() is actually read, and
        // ThreadPoolFactory is where the rule that turns it into a worker count
        // lives — including the -Dopenfps.workers override and the log line
        // that says which rule won. Do not reintroduce the arithmetic here.
        final int logicalCores = sysinfo.logicalProcessorCount();

        final int workerCount = ThreadPoolFactory.resolveWorkerCount(logicalCores);

        LOG.info("System: {} logical cores, {} workers, target rate={} Hz",
            logicalCores, workerCount, config.rate().fps());

        // -- 4. Load or create user profile
        final UserProfile profile = loadOrCreateProfile(userProfile, timePort);

        // -- 5. Event bus
        final I_EventBusPort bus = EventBusFactory.createShared();

        bus.init(DEFAULT_BUS_CAPACITY);

        // -- 6. Worker pool, sized but not yet started.
        //
        // Created BEFORE the subsystems, not after, because the render port
        // needs it: the software rasterizer fans its tile pass out through
        // submitParallel (render/README.md § 7). The registry is handed over
        // empty and filled below — the pool only walks it once start() has run,
        // and that is still the last step here.
        final SubsystemRegistry subsystems = new SubsystemRegistry();

        // Wire the state-change observer to the log bus before any
        // subsystem is registered, so even the first transition of
        // the first subsystem is captured. The observer is bootstrap-
        // and-listen: it sees every transition from this point on.
        subsystems.registerObserver(com.openfps.engine.log.SubsystemStateLogger.install());

        final I_ThreadPoolPort pool = ThreadPoolFactory.createFixed(bus, subsystems);

        pool.init(workerCount);

        // -- 7. Subsystem registry
        final I_RenderPort renderPort = renderPortFactory.createRenderPort(pool, timePort);

        if (renderPort == null)
        {
            throw new IllegalStateException("renderPortFactory returned null");
        }

        subsystems.register(new CoreSubsystem());

        subsystems.register(new MemorySubsystem(memory));

        subsystems.register(new HalSubsystem(inputPort));

        subsystems.register(new NetSubsystem(new NullNetworkPort()));

        // AFTER the render port, deliberately: see this method's Javadoc.
        final I_GameplayPort gameplayPort = gameplayPortFactory.createGameplayPort(inputPort);

        if (gameplayPort == null)
        {
            throw new IllegalStateException("gameplayPortFactory returned null");
        }

        subsystems.register(new GameplaySubsystem(gameplayPort));

        subsystems.register(new RenderSubsystem(renderPort));

        // From the HAL, not hard-coded. Audio is a device the platform owns, so
        // which one you get is the same question as which window you get, and it
        // already has an answer: the factory the launcher handed in. A
        // hard-coded NullAudioPort here meant :desktop and :android could not
        // make a noise however they were wired, which is what it meant until now.
        //
        // AudioSubsystem owns init/shutdown from this point — no factory calls
        // them, by the contract on I_AdapterFactory.getAudioPort.
        subsystems.register(new AudioSubsystem(hal.getAudioPort()));

        subsystems.initAll();

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

        // -- 9. Hand back a live session. Shutdown (drain, pool stop,
        // subsystem teardown, profile save, HAL and memory release) all
        // belongs to EngineSession.stop(), so a caller that cannot block
        // gets the same teardown as one that can.
        return new EngineSession(memory, hal, bus, subsystems, pool, loop,
            loopThread, userProfile, profile, timePort, window);
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
     *
     * Package-private: {@link EngineSession} owns teardown and this is part
     * of it. On Android it runs from {@code onDestroy}, which is why the
     * profile write must not assume a graceful window close ever happened.
     */
    static void saveProfile(final I_UserProfilePort port, final UserProfile profile,
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
