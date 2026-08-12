/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * The logback appender that bridges every SLF4J call in the engine
 * to the engine's own log bus.
 *
 * <p>The appender is the single point of contact between the SLF4J
 * facade (used in 175 places across the engine) and the engine's
 * log bus. Adding this appender to logback's root logger means
 * every existing {@code LOG.info(...)} call flows through the bus
 * automatically &mdash; no call site has to change.</p>
 *
 * <h2>How the source is mapped</h2>
 *
 * <p>SLF4J calls carry a logger name (the fully-qualified class).
 * The bridge maps the prefix of that name to a subsystem source:</p>
 *
 * <pre>
 *   com.openfps.engine.core.EngineMain          -> "engine.core"
 *   com.openfps.engine.gameplay.Match           -> "engine.gameplay"
 *   com.openfps.engine.net.NetArgs              -> "engine.net"
 *   com.openfps.tools.MapThumbnailMain          -> "tools"
 *   anything outside com.openfps                 -> "external"
 * </pre>
 *
 * <p>The mapping is the same one the per-subsystem buses use, so a
 * log line that started in {@code LOG.info("...","com.openfps.engine.core.EngineMain")}
 * is delivered to the bus with {@code source = "engine.core"}.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>SLF4J is called from many threads. The bridge is invoked on the
 * calling thread; the bus's {@link I_LogBus#publish} is thread-safe;
 * the subscriber list is a {@code CopyOnWriteArrayList} so a slow
 * subscriber does not break the bus.</p>
 */
public final class Slf4jLogBusBridge extends AppenderBase<ILoggingEvent>
{
    /** Common Java package prefix on every engine class; stripped
     *  before matching against {@link LogBusFactory#allSubsystems()}
     *  keys so {@code com.openfps.engine.core.EngineMain} lands on
     *  the {@code engine.core} source. */
    private static final String LOGGER_PREFIX = "com.openfps.";

    /** The log bus every event is published to. Never null. */
    private final I_LogBus bus;

    /** Whether the appender has been started by logback. */
    private boolean started;

    /**
     * Builds a bridge that publishes to the given bus.
     *
     * @param bus the target bus; must not be null
     * @throws IllegalArgumentException if {@code bus} is null
     */
    public Slf4jLogBusBridge(final I_LogBus bus)
    {
        if (bus == null)
        {
            throw new IllegalArgumentException("bus must not be null");
        }

        this.bus = bus;
    }

    /**
     * Builds a bridge that publishes to the engine's main bus. The
     * main bus is the one the {@link LogBusFactory#main} accessor
     * hands out; an appender created this way survives factory
     * resets only if the factory has not been reset since the bridge
     * was created.
     *
     * @return a bridge ready to be added to logback
     */
    public static Slf4jLogBusBridge toMainBus()
    {
        return new Slf4jLogBusBridge(LogBusFactory.main());
    }

    @Override
    public void start()
    {
        started = true;

        super.start();
    }

    @Override
    public void stop()
    {
        started = false;

        super.stop();
    }

    @Override
    protected void append(final ILoggingEvent event)
    {
        if (!started || event == null)
        {
            return;
        }

        final LogLevel level = mapLevel(event.getLevel());

        // Drop TRACE/DEBUG below the bus's default sink level, if
        // the sink ever wants a floor. The bus itself does not enforce
        // a floor; the sink does. We pass everything through and
        // let the consumer decide.
        //
        // ThrowableProxy is the concrete class behind IThrowableProxy;
        // it carries the original Throwable, the marker interface does
        // not. A null here is fine -- the message is already formatted
        // with the exception class and stack -- but we get the cause
        // when we can for sinks that want to introspect it.
        final Throwable cause;

        if (event.getThrowableProxy() instanceof ch.qos.logback.classic.spi.ThrowableProxy)
        {
            cause = ((ch.qos.logback.classic.spi.ThrowableProxy) event.getThrowableProxy()).getThrowable();
        }
        else
        {
            cause = null;
        }

        final LogEvent out = new LogEvent(
            event.getTimeStamp(),
            sourceFor(event.getLoggerName()),
            event.getLoggerName(),
            level,
            event.getFormattedMessage(),
            cause);

        try
        {
            bus.publish(out);
        }
        catch (final RuntimeException e)
        {
            // A publishing failure must not propagate into the
            // caller's stack and break the program. Log to stderr
            // and carry on. The dropped counter on the bus is the
            // proper way to see the loss; the stderr line is the
            // last-resort hint that something is wrong.
            System.err.println("Slf4jLogBusBridge publish failed: " + e);
        }
    }

    /**
     * Maps an SLF4J logger name to the engine's subsystem source.
     *
     * @param logger the fully-qualified logger name; must not be null
     * @return the subsystem source; never null
     */
    static String sourceFor(final String logger)
    {
        if (logger == null)
        {
            return "external";
        }

        // SLF4J logger names are fully-qualified Java packages:
        // "com.openfps.engine.core.EngineMain". The engine's
        // subsystem names are the package suffix:
        // "engine.core", "engine.hal". Strip the project's
        // "com.openfps." prefix and pick the longest matching
        // subsystem name; everything else is "external".
        final String stripped;

        if (logger.startsWith(LOGGER_PREFIX))
        {
            stripped = logger.substring(LOGGER_PREFIX.length());
        }
        else
        {
            stripped = logger;
        }

        String best = "external";

        for (final String candidate : LogBusFactory.allSubsystems().keySet())
        {
            if (stripped.startsWith(candidate) && candidate.length() > best.length())
            {
                best = candidate;
            }
        }

        return best;
    }

    private static LogLevel mapLevel(final Level level)
    {
        if (level == null)
        {
            return LogLevel.INFO;
        }

        if (level == Level.ERROR)
        {
            return LogLevel.ERROR;
        }

        if (level == Level.WARN)
        {
            return LogLevel.WARN;
        }

        if (level == Level.INFO)
        {
            return LogLevel.INFO;
        }

        if (level == Level.DEBUG)
        {
            return LogLevel.DEBUG;
        }

        return LogLevel.TRACE;
    }
}
