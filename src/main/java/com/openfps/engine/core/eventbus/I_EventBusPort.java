/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.eventbus;

import com.openfps.engine.core.event.I_EngineEvent;

/**
 * Port interface for the engine event bus.
 *
 * The bus is the single point of communication between producers
 * (GameLoop, network, input) and consumers (the worker pool). The
 * default implementation is a single shared queue (see
 * {@code SharedEventBus}). Per-subsystem or work-stealing variants are
 * future options behind the same interface.
 *
 * Backpressure: publish() BLOCKS the producer when the queue is full.
 * This is the contract — chosen because losing events silently is
 * worse than briefly stalling a producer.
 *
 * State machine:
 *   UNINITIALIZED ──init(capacity)──► READY ──drain()──► DRAINING ──shutdown()──► SHUTDOWN
 */
public interface I_EventBusPort
{
    /**
     * Lifecycle: UNINITIALIZED → READY.
     * @param capacity maximum queue size (1 or greater)
     */
    void init(int capacity);

    /** Lifecycle: any → SHUTDOWN. After this, all operations throw. */
    void shutdown();

    /**
     * Lifecycle: READY → DRAINING. No new events accepted; workers
     * drain remaining events then exit.
     */
    void drain();

    /**
     * Publishes an event. BLOCKS if the queue is full (backpressure).
     *
     * @param event the event to publish
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the bus is not READY
     */
    void publish(I_EngineEvent event) throws InterruptedException;

    /**
     * Takes the next event. BLOCKS until one is available, or returns
     * null if the bus is in DRAINING state with no events remaining.
     *
     * @return the next event, or null if drained
     * @throws InterruptedException if interrupted while waiting
     */
    I_EngineEvent take() throws InterruptedException;

    /** Returns the current queue size. */
    int pendingCount();

    /** Returns the configured capacity. */
    int capacity();

    /** Returns the bus state. */
    State state();

    enum State
    {
        UNINITIALIZED,
        READY,
        DRAINING,
        SHUTDOWN
    }
}
