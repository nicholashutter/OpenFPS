/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

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

    // The drain loop. Wakes every DRAIN_INTERVAL_MS, reads each
    // subsystem's recent events, and republishes them to the main
    // bus. "Recent" is the bus's ring buffer, so an event that has
    // not been drained in the interval is dropped, the same as any
    // other overflow.
    private static void drainLoop()
    {
        long lastReadIndex = 0L;

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
                final int recent = bus.recent(Integer.MAX_VALUE).size();

                for (int i = 0; i < recent; i++)
                {
                    // We drain the per-subsystem events one-by-one so
                    // a slow main bus can be dropped instead of
                    // blocking the drain thread.
                    final List<LogEvent> events = bus.recent(recent);

                    if (i >= events.size())
                    {
                        break;
                    }

                    final LogEvent event = events.get(i);

                    mainBus.publish(event);
                }
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
