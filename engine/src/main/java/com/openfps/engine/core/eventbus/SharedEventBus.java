/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.eventbus;

import com.openfps.engine.core.event.I_EngineEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Single-shared-queue event bus. All producers and consumers share one
 * {@link LinkedBlockingQueue}. {@code publish()} blocks on a full queue
 * (backpressure); {@code take()} blocks on an empty queue.
 *
 * Tradeoffs:
 *   + Simple. One queue, one monitor.
 *   + FIFO across all producers.
 *   − Contention point at high event rates.
 *   − One slow consumer can block others (mitigated by worker pool size).
 *
 * At the 120 Hz worst case the GameLoop produces 120 events/sec, and even
 * with render and network events wired the total stays in the low
 * thousands per second. Way under contention limits for a single queue.
 */
public final class SharedEventBus implements I_EventBusPort
{
    private static final Logger LOG = LoggerFactory.getLogger(SharedEventBus.class);

    /** Backing queue, swapped on {@link #init(int)}. MUTABLE. */
    private LinkedBlockingQueue<I_EngineEvent> queue;
    /** Set once at {@link #init(int)}, read by {@link #capacity()}. MUTABLE. */
    private int capacity;
    /** Lifecycle state. MUTABLE: read by the worker threads, written by {@link #init()}, {@link #shutdown()}. */
    private volatile State state;

    public SharedEventBus()
    {
        this.state = State.UNINITIALIZED;
    }

    @Override
    public void init(final int capacity)
    {
        if (state != State.UNINITIALIZED)
        {
            throw new IllegalStateException("init() called from state " + state
                + " — only valid from UNINITIALIZED");
        }

        if (capacity < 1)
        {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }

        this.capacity = capacity;

        this.queue = new LinkedBlockingQueue<>(capacity);

        this.state = State.READY;

        LOG.info("SharedEventBus initialized: capacity={} events", capacity);
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN — already terminal");
        }

        final State prev = state;

        state = State.SHUTDOWN;

        // Discard remaining events to allow blocked take() calls to exit.
        // Workers should already be exiting on drain() so this should be small.
        // Count BEFORE clearing — otherwise the log always reports zero.
        int discarded = 0;

        if (queue != null)
        {
            discarded = queue.size();

            queue.clear();
        }

        LOG.info("SharedEventBus shut down (was {}, discarded={} events)", prev, discarded);
    }

    @Override
    public void drain()
    {
        if (state != State.READY)
        {
            throw new IllegalStateException("drain() called from state " + state
                + " — only valid from READY");
        }

        state = State.DRAINING;

        LOG.info("SharedEventBus draining: {} events remaining", queue.size());
    }

    @Override
    public void publish(final I_EngineEvent event) throws InterruptedException
    {
        if (state != State.READY)
        {
            throw new IllegalStateException("publish() called from state " + state
                + " — only valid from READY");
        }

        if (event == null)
        {
            throw new IllegalArgumentException("event must not be null");
        }

        // LinkedBlockingQueue.put blocks when full — this is the backpressure.
        queue.put(event);
    }

    @Override
    public I_EngineEvent take() throws InterruptedException
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("take() called from state SHUTDOWN — bus is closed");
        }

        if (state == State.DRAINING)
        {
            // Return null if drained, otherwise keep draining
            return queue.poll();
        }

        // LinkedBlockingQueue.take blocks when empty.
        return queue.take();
    }

    @Override
    public int pendingCount()
    {
        if (queue == null)
        {
            return 0;
        }

        return queue.size();
    }

    @Override
    public int capacity()
    {
        return capacity;
    }

    @Override
    public State state()
    {
        return state;
    }
}
