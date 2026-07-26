/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.common.Constants;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.I_NetworkPort;
import com.openfps.engine.hal.port.I_TimePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D_ Main game loop.
 * Maintains tic timing and calls each subsystem in order.
 * Runs on a single dedicated thread at a fixed TIC_RATE.
 */
public final class GameLoop implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(GameLoop.class);

    private final I_TimePort timePort;
    private final I_InputPort inputPort;
    private final I_NetworkPort networkPort;

    /** Current tic number, monotonically increasing. */
    private int currentTic;

    /** True once shutdown() has been called. */
    private volatile boolean running;

    // MUTABLE: thread-scratch tic timing
    private long nextTicDeadlineNanos;
    private long frameStartNanos;

    public GameLoop(
        final I_TimePort timePort,
        final I_InputPort inputPort,
        final I_NetworkPort networkPort)
    {
        this.timePort = timePort;
        this.inputPort = inputPort;
        this.networkPort = networkPort;
        this.currentTic = 0;
        this.running = false;
    }

    /**
     * Starts the game loop on the calling thread.
     * Blocks until shutdown() is called from another thread.
     */
    @Override
    public void run()
    {
        running = true;
        nextTicDeadlineNanos = timePort.nanos() + Constants.NANOS_PER_TIC;

        LOG.info("Game loop started, TIC_RATE={} Hz, NANOS_PER_TIC={}",
            Constants.TIC_RATE, Constants.NANOS_PER_TIC);

        while (running)
        {
            frameStartNanos = timePort.nanos();

            // Spin-wait until deadline (simple; replace with park+timeout for prod)
            if (frameStartNanos < nextTicDeadlineNanos)
            {
                final long waitNanos = nextTicDeadlineNanos - frameStartNanos;
                if (waitNanos > 1_000_000L)  // > 1ms
                {
                    sleepNanos(waitNanos - 1_000_000L);
                }
                // else busy-spin for sub-ms precision
                while (timePort.nanos() < nextTicDeadlineNanos)
                {
                    Thread.onSpinWait();
                }
            }

            final long ticStartNanos = timePort.nanos();
            final int thisTic = currentTic;

            // ---- Subsystem ticks (P_, G_, W_, R_, S_ called here) ----

            // G_ Networking — send/receive tic commands
            networkPort.processTic(thisTic);

            // P_ Input — sample input and populate tic commands
            inputPort.sampleInput(thisTic);

            // Advance tic counter
            currentTic = thisTic + 1;

            // Advance deadline for next tic
            nextTicDeadlineNanos += Constants.NANOS_PER_TIC;

            final long ticElapsed = timePort.nanos() - ticStartNanos;
            if (ticElapsed > Constants.NANOS_PER_TIC)
            {
                LOG.warn("Tic {} took {} ns (budget {} ns) — budget exceeded",
                    thisTic, ticElapsed, Constants.NANOS_PER_TIC);
            }
        }

        LOG.info("Game loop stopped at tic {}", currentTic);
    }

    /**
     * Signals the loop to stop. Called from another thread.
     */
    public void shutdown()
    {
        running = false;
        LOG.info("Shutdown requested.");
    }

    /** Returns the current tic number. Thread-safe read. */
    public int currentTic()
    {
        return currentTic;
    }

    /** Returns true if the loop is running. */
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
