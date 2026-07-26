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
 * For 8 workers (cores/2) and 35 Hz tick rate, the expected load is
 * ~280 events/sec across all event types. Way under contention limits.
 */
public final class SharedEventBus implements I_EventBusPort
{
    private static final Logger LOG = LoggerFactory.getLogger(SharedEventBus.class);

    private LinkedBlockingQueue<I_EngineEvent> queue;
    private int capacity;
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
        // Drain remaining events to allow blocked take() calls to exit.
        // Workers should already be exiting on drain() so this should be small.
        if (queue != null)
        {
            queue.clear();
        }
        LOG.info("SharedEventBus shut down (was {}, drained={} events)", prev,
            queue == null ? 0 : queue.size());
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
        return queue == null ? 0 : queue.size();
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
