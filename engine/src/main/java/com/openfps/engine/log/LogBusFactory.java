/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place the engine keeps a reference to every {@link I_LogBus}.
 *
 * <p>The shape mirrors {@code EventBusFactory}: a static accessor
 * with a fixed set of named buses and a single {@link #main} queue
 * that drains them. Producers call {@code LogBusFactory.core().info(...)}
 * (or whichever subsystem they belong to); consumers subscribe to
 * {@link #main} and get every event from every subsystem, stamped
 * with the subsystem's source name.</p>
 *
 * <h2>Initialisation</h2>
 *
 * <p>The factory is lazy: the first call to {@link #main} (or to any
 * of the per-subsystem accessors) builds the main bus and the named
 * subsystem buses. Subsequent calls return the same instances. The
 * factory is reset by {@link #resetForTesting} only &mdash; production
 * code never resets it.</p>
 *
 * <h2>The drain task</h2>
 *
 * <p>A background thread started by {@link #startDrainTask()} polls
 * each per-subsystem bus every {@link #DRAIN_INTERVAL_MS} millis,
 * drains its recent events, and republishes them to the main bus.
 * That is the "all per-subsystem logs are read into the main queue"
 * behaviour the player asked for: a single subscriber on the main
 * bus sees every log line from every subsystem, in the order it
 * arrived at the subsystem.</p>
 */
public final class LogBusFactory
{
    /** Default capacity of the main log ring buffer. */
    public static final int DEFAULT_MAIN_CAPACITY = 500;

    /** Default capacity of each per-subsystem ring buffer. */
    public static final int DEFAULT_SUBSYSTEM_CAPACITY = 100;

    /** How often the drain task wakes to pull from each subsystem. */
    public static final long DRAIN_INTERVAL_MS = 100L;

    /** The fixed set of subsystem names. Adding a new subsystem is a
     *  one-line change here. */
    private static final String[] SUBSYSTEM_NAMES =
    {
        "engine.core",
        "engine.hal",
        "engine.memory",
        "engine.gameplay",
        "engine.net",
        "engine.audio",
        "engine.render",
        "engine.demo",
        "engine.map",
    };

    /** The main bus, or null until {@link #main} is called. */
    private static volatile RingBufferLogBus mainBus;

    /** The per-subsystem buses, keyed by source name. */
    private static volatile Map<String, SubsystemLogBus> subsystemBuses;

    /** The background drain task, or null until {@link #startDrainTask}
     *  is called. */
    private static volatile Thread drainThread;

    /** Whether the drain task is running. */
    private static volatile boolean drainRunning;

    /** The current file sink, or null until {@link #installDefaultFileSink}
     *  is called. */
    private static volatile LogFileSink fileSink;

    private LogBusFactory()
    {
        // Static utility.
    }

    /**
     * Returns the main log bus, building it on first call.
     *
     * @return the singleton main bus; never null after the first call
     */
    public static I_LogBus main()
    {
        RingBufferLogBus local = mainBus;

        if (local == null)
        {
            synchronized (LogBusFactory.class)
            {
                local = mainBus;

                if (local == null)
                {
                    local = new RingBufferLogBus(DEFAULT_MAIN_CAPACITY);

                    mainBus = local;

                    subsystemBuses = buildSubsystemBuses(local);
                }
            }
        }

        return local;
    }

    /**
     * Returns the bus for the named subsystem. The subsystem name
     * is one of the constants in {@link #SUBSYSTEM_NAMES}; anything
     * else returns a transient bus the factory will not poll.
     *
     * @param source the subsystem name; must not be null or blank
     * @return the subsystem bus; never null
     */
    public static I_LogBus subsystem(final String source)
    {
        if (source == null || source.isBlank())
        {
            throw new IllegalArgumentException("source must not be blank");
        }

        // Touch main() to make sure subsystemBuses is built.
        main();

        return subsystemBuses.get(source);
    }

    /**
     * Starts the background drain task. Idempotent: a second call
     * is a no-op while the first is running.
     */
    public static void startDrainTask()
    {
        if (drainRunning)
        {
            return;
        }

        synchronized (LogBusFactory.class)
        {
            if (drainRunning)
            {
                return;
            }

            // Touch main() to ensure the subsystem buses exist.
            main();

            drainRunning = true;

            drainThread = new Thread(LogBusFactory::drainLoop, "openfps-log-drain");

            drainThread.setDaemon(true);

            drainThread.start();
        }
    }

    /**
     * Stops the background drain task. Idempotent. After this call,
     * subsystem bus events no longer flow to the main bus.
     */
    public static void stopDrainTask()
    {
        drainRunning = false;

        if (drainThread != null)
        {
            drainThread.interrupt();

            drainThread = null;
        }
    }

    /**
     * Resets the factory. Intended for tests only &mdash; a production
     * engine never resets because the buses are shared singletons.
     */
    public static void resetForTesting()
    {
        synchronized (LogBusFactory.class)
        {
            stopDrainTask();

            closeFileSink();

            if (mainBus != null)
            {
                mainBus.close();
            }

            if (subsystemBuses != null)
            {
                for (final SubsystemLogBus bus : subsystemBuses.values())
                {
                    bus.close();
                }
            }

            mainBus = null;

            subsystemBuses = null;
        }
    }

    /**
     * Returns the snapshot of every per-subsystem bus. The map is
     * immutable; the buses inside it are the same instances the
     * factory hands out, so a subscriber on a returned bus sees the
     * same events the factory does.
     *
     * @return an unmodifiable map of source name to bus
     */
    public static Map<String, I_LogBus> allSubsystems()
    {
        main();

        return Collections.unmodifiableMap(new LinkedHashMap<>(subsystemBuses));
    }

    // ---------------------------------------------------------------------
    // LogFileSink installation
    // ---------------------------------------------------------------------

    /**
     * Installs a {@link LogFileSink} using {@link LogSinkPaths}'s
     * three-source resolution policy. Idempotent: a second call
     * returns the existing sink without restarting anything.
     *
     * <p>The sink ignores {@link LogFileSink#DISABLED_SENTINEL}
     * (a literal {@code "off"} value in either the system property
     * or the env var), which is the way a developer turns the
     * file sink off without removing the bootstrap call.</p>
     *
     * @return the installed sink, or null if the sink was disabled
     *     via the {@code off} sentinel
     */
    public static LogFileSink installDefaultFileSink()
    {
        // The path resolution reads the system property / env var
        // every install. Tests can flip the system property between
        // calls and see the new path; production calls once at boot.
        final Path path = LogSinkPaths.resolve();

        final String raw = rawSinkToggle();

        if (LogFileSink.DISABLED_SENTINEL.equalsIgnoreCase(raw))
        {
            return null;
        }

        final LogFileSink existing = fileSink;

        if (existing != null)
        {
            return existing;
        }

        synchronized (LogBusFactory.class)
        {
            if (fileSink != null)
            {
                return fileSink;
            }

            final LogFileSink created = new LogFileSink(path);

            created.start();

            fileSink = created;

            return created;
        }
    }

    /**
     * Returns the current file sink, or null if {@link
     * #installDefaultFileSink} has not been called (or has been
     * disabled). Used by callers that want to check whether
     * logging is wired and by the {@code DesktopLauncher}'s
     * shutdown path.
     *
     * @return the current sink, or null
     */
    public static LogFileSink fileSink()
    {
        return fileSink;
    }

    /**
     * Closes the file sink if one is installed. Idempotent.
     * Used by {@link #resetForTesting} and by launchers on shutdown.
     */
    public static void closeFileSink()
    {
        final LogFileSink existing = fileSink;

        if (existing == null)
        {
            return;
        }

        synchronized (LogBusFactory.class)
        {
            if (fileSink == null)
            {
                return;
            }

            existing.close();

            fileSink = null;
        }
    }

    // Reads the raw value of the system property or env var that
    // gates the sink, so installDefaultFileSink can recognize the
    // "off" sentinel without going through LogSinkPaths.
    private static String rawSinkToggle()
    {
        final String prop = System.getProperty(LogSinkPaths.SYSTEM_PROPERTY);

        if (prop != null && !prop.isBlank())
        {
            return prop.trim();
        }

        final String env = System.getenv(LogSinkPaths.ENV_VARIABLE);

        if (env != null && !env.isBlank())
        {
            return env.trim();
        }

        return null;
    }

    // The drain loop. Wakes every DRAIN_INTERVAL_MS and forwards
    // every subsystem bus's local-ring events to the main bus.
    //
    // The events go through drain(), which is consume-and-clear:
    // each event is published to main exactly once. SubsystemLogBus
    // also forwards to main synchronously inside publish(), which
    // means the events reach main before the drain runs; the drain
    // exists only to clear the subsystem-local rings so a slow
    // subscriber on a subsystem bus (not on the main one) can
    // observe the events that passed through. Direct main-bus
    // subscribers — the file sink, the debug overlay — see each
    // event once, not twice.
    private static void drainLoop()
    {
        while (drainRunning && !Thread.currentThread().isInterrupted())
        {
            try
            {
                Thread.sleep(DRAIN_INTERVAL_MS);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();

                break;
            }

            for (final SubsystemLogBus bus : subsystemBuses.values())
            {
                // drain() empties the local ring. The events are
                // already on the main bus via SubsystemLogBus's
                // synchronous forward, so this is bookkeeping for
                // any subsystem-local subscribers and does not
                // republish to main.
                bus.drain();
            }
        }
    }

    // One subsystem bus per fixed name. The map preserves the
    // declaration order so debug overlays that iterate it get a
    // stable subsystem order.
    private static Map<String, SubsystemLogBus> buildSubsystemBuses(final I_LogBus target)
    {
        final Map<String, SubsystemLogBus> built = new LinkedHashMap<>(SUBSYSTEM_NAMES.length);

        for (final String name : SUBSYSTEM_NAMES)
        {
            built.put(name, new SubsystemLogBus(name, target, DEFAULT_SUBSYSTEM_CAPACITY));
        }

        return built;
    }
}
