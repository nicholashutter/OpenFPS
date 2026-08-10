/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.util.List;
import java.util.function.Consumer;

/**
 * One queue of {@link LogEvent}s, fed by producers and drained by
 * consumers.
 *
 * <p>The bus is the spine of the engine's logging: producers call
 * {@link #publish}, subscribers receive events live, and a debug
 * overlay or file writer drains the recent ring buffer on demand.
 * The bus is the log-side analogue of the engine's
 * {@code I_EventBusPort}: same shape (publish + subscribe + drain),
 * same producer/consumer split, but the contract is
 * <strong>non-blocking</strong>. A logging call that backs up the
 * caller is worse than a logging call that drops a line.</p>
 *
 * <h2>Backpressure</h2>
 *
 * <p>{@link #publish} NEVER blocks. If the bus is full it drops the
 * new event and increments a dropped counter that a subscriber can
 * read. The counter is what makes silent loss observable.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are safe from any thread. {@link #publish} may be
 * called from the render thread, the game thread, or any worker;
 * subscribers may be invoked on the calling thread or asynchronously
 * depending on the implementation.</p>
 */
public interface I_LogBus
{
    /**
     * Publishes an event to the bus. Never blocks.
     *
     * @param event the event to publish; must not be null
     * @throws IllegalArgumentException if {@code event} is null
     */
    void publish(LogEvent event);

    /**
     * Subscribes a handler to live events. The handler is invoked for
     * every event published from this point onward; it is NOT invoked
     * for events that arrived before the subscription.
     *
     * <p>The handler is invoked synchronously on the calling thread
     * for {@link #publish} (unless the implementation documents
     * otherwise). A slow handler therefore back-pressures
     * {@link #publish} on the calling thread &mdash; which is the
     * reason the engine's own log sinks do their file I/O on a
     * background thread.</p>
     *
     * @param handler the handler; must not be null
     * @return a subscription handle that can be used to unsubscribe
     * @throws IllegalArgumentException if {@code handler} is null
     */
    LogSubscription subscribe(Consumer<LogEvent> handler);

    /**
     * Returns the most recent events, newest first. Used by on-demand
     * consumers like a debug overlay that polls rather than streams.
     *
     * @param max the maximum number of events to return; must be positive
     * @return a snapshot list, never null, possibly empty
     * @throws IllegalArgumentException if {@code max} is not positive
     */
    List<LogEvent> recent(int max);

    /**
     * Returns the number of events this bus has dropped because its
     * ring buffer was full. Useful for a debug overlay that wants to
     * surface back-pressure.
     *
     * @return the dropped-event count, never negative
     */
    long droppedCount();

    /**
     * Closes the bus. After this call, {@link #publish} and
     * {@link #subscribe} are no-ops. Idempotent.
     */
    void close();
}
