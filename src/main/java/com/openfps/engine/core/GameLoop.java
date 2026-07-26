/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.common.Constants;
import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.ShutdownEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.hal.port.I_TimePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D_ Game loop — now a thin event PRODUCER.
 *
 * The loop runs on a single dedicated thread. Each iteration:
 *   1. Sleeps until the next tic deadline (spin-wait for sub-ms precision)
 *   2. Builds a {@link TickEvent} with the current tic number and delta
 *   3. Publishes the event to the bus (blocks if the bus is full)
 *
 * On shutdown, the loop publishes a {@link ShutdownEvent} and exits. It
 * no longer calls any subsystem directly — the worker pool does that.
 *
 * This design lets the engine scale: the loop produces events at a
 * fixed cadence; workers consume and dispatch them in parallel.
 */
public final class GameLoop implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(GameLoop.class);

    private final I_TimePort timePort;
    private final I_EventBusPort bus;
    private final EventFactory eventFactory;

    /** Current tic number, monotonically increasing. */
    private int currentTic;

    /** Max tics before auto-shutdown. {@code <= 0} means run forever. */
    private final int maxTics;

    /** True once shutdown() has been called or maxTics reached. */
    private volatile boolean running;

    public GameLoop(final I_TimePort timePort, final I_EventBusPort bus,
                    final EventFactory eventFactory, final int maxTics)
    {
        this.timePort = timePort;
        this.bus = bus;
        this.eventFactory = eventFactory;
        this.maxTics = maxTics;
        this.currentTic = 0;
        this.running = false;
    }

    @Override
    public void run()
    {
        running = true;
        long nextDeadlineNanos = timePort.nanos() + Constants.NANOS_PER_TIC;

        LOG.info("GameLoop started: TIC_RATE={} Hz, maxTics={}",
            Constants.TIC_RATE, maxTics <= 0 ? "infinite" : maxTics);

        while (running)
        {
            final long now = timePort.nanos();
            if (now < nextDeadlineNanos)
            {
                final long waitNanos = nextDeadlineNanos - now;
                if (waitNanos > 1_000_000L)
                {
                    sleepNanos(waitNanos - 1_000_000L);
                }
                while (timePort.nanos() < nextDeadlineNanos)
                {
                    Thread.onSpinWait();
                }
            }

            final int thisTic = currentTic++;
            final long ticStartNanos = timePort.nanos();
            final long deltaNanos = ticStartNanos - (nextDeadlineNanos - Constants.NANOS_PER_TIC);

            final TickEvent tick = eventFactory.newTick(thisTic, deltaNanos);
            try
            {
                bus.publish(tick);
            }
            catch (final InterruptedException e)
            {
                LOG.info("GameLoop interrupted — exiting");
                Thread.currentThread().interrupt();
                break;
            }

            nextDeadlineNanos += Constants.NANOS_PER_TIC;

            // Check auto-shutdown
            if (maxTics > 0 && thisTic + 1 >= maxTics)
            {
                LOG.info("GameLoop reached maxTics={} — emitting SHUTDOWN", maxTics);
                publishShutdown("maxTics reached");
                break;
            }
        }

        running = false;
        LOG.info("GameLoop stopped at tic {}", currentTic);
    }

    /** Signals the loop to stop. Called from another thread. */
    public void shutdown()
    {
        running = false;
    }

    /** Publishes a SHUTDOWN event so the engine can drain and stop. */
    private void publishShutdown(final String reason)
    {
        final ShutdownEvent event = eventFactory.newShutdown(reason);
        try
        {
            bus.publish(event);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public int currentTic()
    {
        return currentTic;
    }

    public boolean isRunning()
    {
        return running;
    }

    private void sleepNanos(final long nanos)
    {
        final long ms = nanos / 1_000_000L;
        final int ns = (int) (nanos % 1_000_000L);
        try
        {
            Thread.sleep(ms, ns);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
