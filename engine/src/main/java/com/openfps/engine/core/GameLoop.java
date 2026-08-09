/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.RenderFrameEvent;
import com.openfps.engine.core.event.ShutdownEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.hal.port.I_TimePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D_ Game loop — produces events at a configurable rate.
 *
 * The loop runs on a single dedicated thread. Each iteration:
 *   1. Computes the absolute deadline for this tic:
 *          deadlineNanos = startNanos + (tic * nanosPerFrame)
 *      This is the drift-correction technique — see FrameRate.java.
 *   2. Sleeps / spin-waits until the deadline.
 *   3. Builds a {@link TickEvent} and publishes it to the bus.
 *
 * The loop self-terminates after {@code config.maxTics()} tics, at
 * which point it publishes a {@link ShutdownEvent} so the engine can
 * drain and stop.
 *
 * For production, pass {@code GameConfig.unbounded(rate)} and trigger
 * shutdown via an external {@code ShutdownEvent} (UI close, signal,
 * or programmatic).
 *
 * Threading note: the loop sleeps via {@link Thread#sleep(long, int)}
 * for the bulk of the wait (good for power and CPU temperature), then
 * uses {@link Thread#onSpinWait()} for the sub-millisecond remainder
 * (Windows sleep granularity is ~15 ms, so spin-wait is required for
 * 120 fps and below).
 */
public final class GameLoop implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(GameLoop.class);

    private final I_TimePort timePort;
    private final I_EventBusPort bus;
    private final EventFactory eventFactory;
    private final GameConfig config;

    /** True once shutdown() has been called or maxTics reached. MUTABLE: read by the loop thread, written by shutdown() and the loop body's terminal path. */
    private volatile boolean running;

    public GameLoop(final I_TimePort timePort,
                    final I_EventBusPort bus,
                    final EventFactory eventFactory,
                    final GameConfig config)
    {
        if (timePort == null)
        {
            throw new IllegalArgumentException("timePort must not be null");
        }
        if (bus == null)
        {
            throw new IllegalArgumentException("bus must not be null");
        }
        if (eventFactory == null)
        {
            throw new IllegalArgumentException("eventFactory must not be null");
        }
        if (config == null)
        {
            throw new IllegalArgumentException("config must not be null");
        }
        this.timePort = timePort;
        this.bus = bus;
        this.eventFactory = eventFactory;
        this.config = config;
        this.running = false;
    }

    @Override
    public void run()
    {
        running = true;

        // Origin for absolute deadline math. Captured once; every
        // subsequent deadline is computed from this.
        final long startNanos = timePort.nanos();
        final long nanosPerTic = config.nanosPerTic();

        LOG.info("GameLoop started: rate={} ({}ns/tic), maxTics={}",
            config.rate(), nanosPerTic, config.maxTics());

        int tic = 0;
        while (running)
        {
            // -- 1. Compute absolute deadline for this tic
            final long deadlineNanos = startNanos + (tic * nanosPerTic);

            // -- 2. Sleep + spin-wait until deadline
            final long waitNanos = deadlineNanos - timePort.nanos();
            if (waitNanos > 1_000_000L)
            {
                sleepNanos(waitNanos - 1_000_000L);
            }
            while (timePort.nanos() < deadlineNanos)
            {
                Thread.onSpinWait();
            }

            // -- 3. Build and publish the tick event, then ask for a frame.
            //
            // RenderFrameEvent had a factory method and an event class and a
            // subsystem waiting for it, and nothing ever published one — so
            // R_ was never invoked at all. The loop is the right producer:
            // it is the simulation clock, and render/README.md § 12 makes the
            // platform's onFrame presentation only ("it draws the latest
            // state; it never advances it"). So the frame is produced here,
            // per tic, and the platform uploads whatever the newest finished
            // one is, at whatever rate its display runs.
            //
            // Deliberately UNTHROTTLED. The loop has no idea how long a frame
            // takes and must not wait to find out — blocking here would drag
            // the simulation down to the frame rate and desync a lockstep
            // session. Dropping the requests that cannot be served is
            // RenderSubsystem's job, and it does coalesce them; see its
            // Javadoc for why the consumer is the only party that can.
            final TickEvent tickEvent = eventFactory.newTick(tic, nanosPerTic);
            final RenderFrameEvent renderEvent = eventFactory.newRenderFrame(tic);
            try
            {
                bus.publish(tickEvent);
                bus.publish(renderEvent);
            }
            catch (final InterruptedException e)
            {
                LOG.info("GameLoop interrupted — exiting");
                Thread.currentThread().interrupt();
                break;
            }

            tic++;

            // -- 4. Auto-shutdown at maxTics
            if (tic >= config.maxTics())
            {
                LOG.info("GameLoop reached maxTics={} — emitting SHUTDOWN",
                    config.maxTics());
                publishShutdown("maxTics reached");
                break;
            }
        }

        running = false;
        LOG.info("GameLoop stopped at tic {}", tic);
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
