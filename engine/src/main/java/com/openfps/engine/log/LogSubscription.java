/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A handle returned by {@link I_LogBus#subscribe} that the caller can
 * use to stop receiving events.
 *
 * <p>The handle is the only way to unsubscribe &mdash; the bus keeps
 * no other reference to the handler. Closing the bus unsubscribes
 * everything at once.</p>
 */
public final class LogSubscription
{
    /** The bus the subscription was created on. */
    private final I_LogBus bus;

    /** The handler the subscriber wants called. */
    private final Consumer<LogEvent> handler;

    /** Whether {@link #close} has been called. */
    private boolean closed;

    /**
     * Builds a subscription. Called by the bus implementation, not by
     * callers.
     *
     * @param bus     the bus; must not be null
     * @param handler the handler; must not be null
     */
    LogSubscription(final I_LogBus bus, final Consumer<LogEvent> handler)
    {
        if (bus == null)
        {
            throw new IllegalArgumentException("bus must not be null");
        }

        if (handler == null)
        {
            throw new IllegalArgumentException("handler must not be null");
        }

        this.bus = bus;

        this.handler = handler;
    }

    /**
     * Stops the handler from receiving further events. Idempotent.
     * After this call the subscription is dead and any further call is
     * a no-op.
     */
    public void close()
    {
        if (closed)
        {
            return;
        }

        closed = true;

        if (bus instanceof RingBufferLogBus)
        {
            ((RingBufferLogBus) bus).unsubscribe(this);
        }
    }

    /** Returns true if {@link #close} has been called. */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Returns the handler. Used by the bus when delivering events.
     *
     * @return the handler, never null
     */
    Consumer<LogEvent> handler()
    {
        return handler;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof LogSubscription))
        {
            return false;
        }

        final LogSubscription that = (LogSubscription) other;

        return bus == that.bus && handler.equals(that.handler);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(System.identityHashCode(bus), handler);
    }
}
