/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.util.Objects;

/**
 * One log record: a timestamp, a source, a logger, a level, a message
 * and an optional cause.
 *
 * <p>Immutable, value-typed, and safe to share between threads. The
 * shape mirrors the SLF4J event that the {@link Slf4jLogBusBridge}
 * receives from logback, minus the bits the engine does not need
 * (the MDC, the marker set, the thread name as a separate field &mdash;
 * those are part of the SLF4J interface but the engine's log bus
 * carries a single primary {@code source} field instead).</p>
 *
 * <h2>The {@code source} field</h2>
 *
 * <p>Every event carries a {@code source} string that names which
 * subsystem produced it: {@code "engine.core"}, {@code "engine.gameplay"},
 * {@code "engine.net"}, and so on. The string is the join key the
 * main log queue uses to route events to the right subscribers and
 * the join key the debug overlay uses to colour-code entries by
 * subsystem.</p>
 *
 * <p>The string is set by the {@link SubsystemLogBus} that wraps the
 * main bus, not by the call site. A caller using SLF4J logs the
 * normal way; the logback bridge turns the class name into the
 * source.</p>
 */
public final class LogEvent
{
    /** A millisecond timestamp from {@link System#currentTimeMillis()}. */
    private final long timestampMs;

    /**
     * The subsystem that produced the event, e.g. {@code "engine.core"}.
     * Never null or blank.
     */
    private final String source;

    /**
     * The fully-qualified logger name, e.g.
     * {@code "com.openfps.engine.core.EngineMain"}. Never null.
     */
    private final String logger;

    /** The level. Never null. */
    private final LogLevel level;

    /** The message, already formatted by SLF4J. May be empty, never null. */
    private final String message;

    /** An optional cause, e.g. for caught exceptions. May be null. */
    private final Throwable cause;

    /**
     * Builds an event.
     *
     * @param timestampMs a millisecond timestamp
     * @param source      the subsystem name; must not be null or blank
     * @param logger      the logger name; must not be null
     * @param level       the level; must not be null
     * @param message     the message; must not be null
     * @param cause       an optional cause; may be null
     * @throws IllegalArgumentException if any non-nullable argument is null
     *         or {@code source} is blank
     */
    public LogEvent(final long timestampMs, final String source, final String logger,
        final LogLevel level, final String message, final Throwable cause)
    {
        if (source == null || source.isBlank())
        {
            throw new IllegalArgumentException("source must not be blank");
        }

        if (logger == null)
        {
            throw new IllegalArgumentException("logger must not be null");
        }

        if (level == null)
        {
            throw new IllegalArgumentException("level must not be null");
        }

        if (message == null)
        {
            throw new IllegalArgumentException("message must not be null");
        }

        this.timestampMs = timestampMs;

        this.source = source;

        this.logger = logger;

        this.level = level;

        this.message = message;

        this.cause = cause;
    }

    /** Returns the timestamp in milliseconds since the epoch. */
    public long timestampMs()
    {
        return timestampMs;
    }

    /** Returns the subsystem name. Never null or blank. */
    public String source()
    {
        return source;
    }

    /** Returns the fully-qualified logger name. Never null. */
    public String logger()
    {
        return logger;
    }

    /** Returns the level. Never null. */
    public LogLevel level()
    {
        return level;
    }

    /** Returns the message. Never null. */
    public String message()
    {
        return message;
    }

    /** Returns the cause, or null if there is no cause. */
    public Throwable cause()
    {
        return cause;
    }

    @Override
    public String toString()
    {
        final StringBuilder out = new StringBuilder(128);

        out.append('[').append(source).append("] ");
        out.append(level.name()).append(' ');
        out.append(logger).append(" - ").append(message);

        if (cause != null)
        {
            out.append(" (").append(cause.getClass().getSimpleName()).append(')');
        }

        return out.toString();
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof LogEvent))
        {
            return false;
        }

        final LogEvent that = (LogEvent) other;

        return timestampMs == that.timestampMs
            && source.equals(that.source)
            && logger.equals(that.logger)
            && level == that.level
            && message.equals(that.message)
            && Objects.equals(cause, that.cause);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(timestampMs, source, logger, level, message, cause);
    }
}
