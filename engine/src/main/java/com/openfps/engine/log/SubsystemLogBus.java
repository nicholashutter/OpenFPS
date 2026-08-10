/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.util.List;
import java.util.function.Consumer;

/**
 * One per-subsystem log queue. Holds the same shape as the main bus
 * but stamps every event with a fixed {@code source} name before
 * forwarding.
 *
 * <p>The point of a per-subsystem bus is two-fold:</p>
 * <ol>
 *   <li><b>Producers can stay system-local.</b> A subsystem that
 *       wants to log without caring about the global bus still has
 *       a typed handle, with the same publish/subscribe contract,
 *       and its events are visible to anyone with a subscription on
 *       the main bus.</li>
 *   <li><b>Subscribers can filter by source.</b> A "net errors only"
 *       consumer subscribes to the main bus and drops everything whose
 *       {@code source} is not {@code "engine.net"}.</li>
 * </ol>
 *
 * <p>The "all per-subsystem queues are read into the main queue" model
 * the player asked for lives in {@link LogBusFactory}: a background
 * drain task polls each subsystem bus, pulls its recent events, and
 * republishes them to the main bus. The subsystem bus itself is
 * non-blocking: {@link #publish} drops on overflow and increments
 * the same dropped counter as the main bus.</p>
 */
public final class SubsystemLogBus implements I_LogBus
{
    /** The bus that events are forwarded to. Never null. */
    private final I_LogBus target;

    /** The fixed source name stamped on every event this bus produces. */
    private final String source;

    /** The local ring buffer, in case the drain task wants a
     *  subsystem-local view. */
    private final RingBufferLogBus local;

    /**
     * Builds a subsystem bus.
     *
     * @param source  the source name; must not be null or blank
     * @param target  the bus to forward events to; must not be null
     * @param capacity the local ring buffer size; must be positive
     * @throws IllegalArgumentException if any argument is null, blank, or
     *     non-positive
     */
    public SubsystemLogBus(final String source, final I_LogBus target, final int capacity)
    {
        if (source == null || source.isBlank())
        {
            throw new IllegalArgumentException("source must not be blank");
        }

        if (target == null)
        {
            throw new IllegalArgumentException("target must not be null");
        }

        if (capacity <= 0)
        {
            throw new IllegalArgumentException("capacity must be positive");
        }

        this.source = source;

        this.target = target;

        this.local = new RingBufferLogBus(capacity);
    }

    /** Returns the source name stamped on every event. */
    public String source()
    {
        return source;
    }

    @Override
    public void publish(final LogEvent event)
    {
        if (event == null)
        {
            throw new IllegalArgumentException("event must not be null");
        }

        // Keep a local copy for the subsystem's own subscribers.
        local.publish(event);

        // Re-stamp the source and forward to the main bus. The event is
        // immutable, so building a copy is safe; copyOnWrite is the
        // cost of a structured logging system, and it is small next
        // to the cost of a typical log message.
        if (source.equals(event.source()))
        {
            target.publish(event);
        }
        else
        {
            final LogEvent stamped = new LogEvent(
                event.timestampMs(),
                source,
                event.logger(),
                event.level(),
                event.message(),
                event.cause());

            target.publish(stamped);
        }
    }

    @Override
    public LogSubscription subscribe(final Consumer<LogEvent> handler)
    {
        return local.subscribe(handler);
    }

    @Override
    public List<LogEvent> recent(final int max)
    {
        return local.recent(max);
    }

    @Override
    public long droppedCount()
    {
        return local.droppedCount();
    }

    @Override
    public void close()
    {
        local.close();
    }
}
