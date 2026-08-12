/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The default {@link I_LogBus} implementation: a fixed-capacity ring
 * buffer of recent events plus a list of live subscribers.
 *
 * <p>Two access paths serve the two consumer shapes:</p>
 * <ul>
 *   <li><b>Live consumers</b> &mdash; a debug overlay, a metrics
 *       counter &mdash; call {@link #subscribe} and receive events as
 *       they arrive.</li>
 *   <li><b>Polling consumers</b> &mdash; a log file writer, a test
 *       assertion &mdash; call {@link #recent} on a tick and read the
 *       last N events.</li>
 * </ul>
 *
 * <p>The ring buffer is the bus's memory budget: a busy engine does
 * not push the heap unbounded. When the buffer is full, the oldest
 * entry is dropped and {@link #droppedCount()} increments. A polling
 * consumer that asks for the last 500 events at one event per tic is
 * well within the budget; a consumer that asks for the last 500 at
 * 60 Hz is a debug-overlay footprint, not a production one.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #publish} acquires the ring-buffer lock briefly. Live
 * subscribers are invoked while the lock is held so a slow subscriber
 * does not race with a subsequent publish. Subscribers should
 * therefore be cheap; an expensive subscriber (a file writer, for
 * example) should hand its events off to a background queue and
 * return immediately.</p>
 */
public final class RingBufferLogBus implements I_LogBus
{
    /** The fixed capacity of the ring buffer. */
    private final int capacity;

    /** The ring buffer. Guarded by {@link #lock}. */
    private final LogEvent[] buffer;

    /** The next write position. Guarded by {@link #lock}. */
    private int writeIndex;

    /** The current number of entries; never larger than {@link #capacity}. */
    private int size;

    /** Guards {@link #buffer}, {@link #writeIndex}, {@link #size}, and
     *  {@link #dropped} during publish. */
    private final ReentrantLock lock = new ReentrantLock();

    /** The number of events dropped because the buffer was full. */
    private long dropped;

    /** Live subscribers; CopyOnWrite so iterate-and-dispatch is safe
     *  even if a handler unsubscribes itself mid-iteration. */
    private final List<LogSubscription> subscribers = new CopyOnWriteArrayList<>();

    /** Whether {@link #close} has been called. */
    private volatile boolean closed;

    /**
     * Builds a bus.
     *
     * @param capacity the ring buffer size; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public RingBufferLogBus(final int capacity)
    {
        if (capacity <= 0)
        {
            throw new IllegalArgumentException("capacity must be positive");
        }

        this.capacity = capacity;

        this.buffer = new LogEvent[capacity];
    }

    @Override
    public void publish(final LogEvent event)
    {
        if (event == null)
        {
            throw new IllegalArgumentException("event must not be null");
        }

        if (closed)
        {
            return;
        }

        final List<Consumer<LogEvent>> toNotify = new ArrayList<>(subscribers.size());

        lock.lock();

        try
        {
            if (size < capacity)
            {
                buffer[writeIndex] = event;

                size++;
            }
            else
            {
                // Drop the oldest entry, overwrite the write position.
                buffer[writeIndex] = event;

                dropped++;
            }

            writeIndex = (writeIndex + 1) % capacity;

            for (final LogSubscription sub : subscribers)
            {
                toNotify.add(sub.handler());
            }
        }
        finally
        {
            lock.unlock();
        }

        // Notify outside the lock so a slow handler does not block
        // the next publish. The CopyOnWrite list makes the iteration
        // safe even if a handler unsubscribes itself.
        for (final Consumer<LogEvent> handler : toNotify)
        {
            try
            {
                handler.accept(event);
            }
            catch (final RuntimeException e)
            {
                // A handler that throws must not break the bus.
                // The bus keeps running; the next publish still goes
                // through. Logging the failure to stderr is the best
                // we can do from here -- this is a programmer bug.
                System.err.println("LogBus subscriber threw: " + e);
            }
        }
    }

    @Override
    public LogSubscription subscribe(final java.util.function.Consumer<LogEvent> handler)
    {
        if (handler == null)
        {
            throw new IllegalArgumentException("handler must not be null");
        }

        final LogSubscription sub = new LogSubscription(this, handler);

        subscribers.add(sub);

        return sub;
    }

    @Override
    public List<LogEvent> recent(final int max)
    {
        if (max <= 0)
        {
            throw new IllegalArgumentException("max must be positive");
        }

        final List<LogEvent> out = new ArrayList<>(Math.min(max, size));

        lock.lock();

        try
        {
            // Walk the ring buffer from the most recent entry backwards.
            final int count = Math.min(max, size);

            int idx = (writeIndex - 1 + capacity) % capacity;

            for (int i = 0; i < count; i++)
            {
                out.add(buffer[idx]);

                idx = (idx - 1 + capacity) % capacity;
            }
        }
        finally
        {
            lock.unlock();
        }

        return out;
    }

    @Override
    public long droppedCount()
    {
        lock.lock();

        try
        {
            return dropped;
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public List<LogEvent> drain()
    {
        final List<LogEvent> out = new ArrayList<>(size);

        lock.lock();

        try
        {
            // Walk the ring from oldest to newest and clear as we
            // go. The walk starts at the position that holds the
            // oldest entry: when the buffer is full that is the
            // write index (it has just been overwritten); when the
            // buffer is not full, it is index 0.
            final int start;

            if (size < capacity)
            {
                start = 0;
            }
            else
            {
                start = writeIndex;
            }

            for (int i = 0; i < size; i++)
            {
                out.add(buffer[(start + i) % capacity]);

                buffer[(start + i) % capacity] = null;
            }

            size = 0;

            writeIndex = 0;
        }
        finally
        {
            lock.unlock();
        }

        return out;
    }

    @Override
    public void close()
    {
        closed = true;

        subscribers.clear();
    }

    /**
     * Removes a subscription. Called by {@link LogSubscription#close}.
     * The bus is the only one that holds the list, so the unsubscribed
     * handler is dropped on the next iteration.
     *
     * @param sub the subscription to remove; must not be null
     */
    void unsubscribe(final LogSubscription sub)
    {
        if (sub == null)
        {
            return;
        }

        subscribers.remove(sub);
    }
}
