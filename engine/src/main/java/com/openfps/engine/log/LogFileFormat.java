/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The on-disk line shape for {@link LogFileSink}.
 *
 * <p>One {@link LogEvent} becomes one or more lines:</p>
 *
 * <pre>
 * [2026-08-11 14:23:45.123] INFO  engine.core  com.openfps.engine.core.EngineMain - Engine booting.
 * [2026-08-11 14:23:46.456] WARN  engine.gameplay  com.openfps.engine.gameplay.Match - subsystem P_ READY -&gt; ERROR
 *     java.lang.IllegalStateException: renderer init failed
 *         at com.openfps.engine.gameplay.Match.foo(Match.java:42)
 *         at com.openfps.engine.gameplay.Match.bar(Match.java:17)
 *         ...
 * </pre>
 *
 * <p>The header line is fixed-width enough for {@code grep}:
 * timestamp, level, source channel, full logger name, message.
 * Stack frames, if any, are pushed below with four-space indent
 * so {@code grep -E "^\["} matches only header lines and a
 * developer's eye sees the trace attached.</p>
 *
 * <h2>Why not logback's {@code PatternLayoutEncoder}</h2>
 *
 * <p>Logback has a perfectly good pattern encoder, but it sits
 * on the SLF4J side of the bridge, not the engine-log-bus side.
 * The {@link Slf4jLogBusBridge} already maps SLF4J calls into
 * {@link LogEvent}s, and a custom encoder on the SLF4J appender
 * would have to be wired around the bridge to share the same
 * source-channel logic. Formatting on the bus side keeps the
 * rule "every consumer sees the same shape" honest: the bridge
 * takes one event into the bus, every consumer &mdash; file,
 * debug overlay, future file rotation &mdash; formats it
 * itself in one place.</p>
 */
public final class LogFileFormat
{
    /** Header-line date format. Locale-fixed so the format is
     *  stable across JVMs. */
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    /** Indent for stack-trace lines. Four spaces is what
     *  {@code PrintWriter.println} would render with a leading
     *  {@code "    "} on each line. */
    private static final String STACK_INDENT = "    ";

    private LogFileFormat()
    {
        // Static utility.
    }

    /**
     * Formats a {@link LogEvent} as a single string with a trailing
     * newline. Stack traces, if present, are indented to four spaces
     * beneath the header line.
     *
     * @param event the event; must not be null
     * @return the formatted string; never null; always ends with {@code "\n"}
     */
    public static String format(final LogEvent event)
    {
        if (event == null)
        {
            throw new IllegalArgumentException("event must not be null");
        }

        final StringBuilder out = new StringBuilder(256);

        out.append('[')
            .append(timestamp(event.timestampMs()))
            .append("] ")
            .append(padLevel(event.level().name()))
            .append(' ')
            .append(event.source())
            .append(' ')
            .append(event.logger())
            .append(" - ")
            .append(event.message())
            .append('\n');

        if (event.cause() != null)
        {
            appendStack(out, event.cause());
        }

        return out.toString();
    }

    /** Formats a millisecond epoch timestamp into the bus's
     *  header-line shape, in the JVM's local time zone. The
     *  bus's timestamps are wall-clock {@link System#currentTimeMillis()}
     *  readings, so showing them in local time is the right call
     *  for a developer reading a log file. A future "send logs
     *  to remote" sink would switch to UTC. */
    private static String timestamp(final long millis)
    {
        final LocalDateTime ldt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());

        return TS.format(ldt);
    }

    /** Pads the level name to five characters with trailing spaces
     *  &mdash; {@code "INFO "} or {@code "ERROR"} unchanged, but
     *  {@code "WARN"} and {@code "TRACE"} become {@code "WARN "} /
     *  {@code "TRACE"} so columns line up in a wide terminal. */
    private static String padLevel(final String name)
    {
        if (name.length() >= 5)
        {
            return name;
        }

        final StringBuilder out = new StringBuilder(5);

        out.append(name);

        while (out.length() < 5)
        {
            out.append(' ');
        }

        return out.toString();
    }

    /** Walks a throwable's stack trace into a {@link StringBuilder}
     *  with every line prefixed by {@link #STACK_INDENT}. The
     *  exception class + message lands on the first line; the
     *  frames below it; the {@code Caused by} chain, if any,
     *  beneath that. */
    private static void appendStack(final StringBuilder out, final Throwable cause)
    {
        final StringWriter sw = new StringWriter();

        final PrintWriter pw = new PrintWriter(sw);

        cause.printStackTrace(pw);

        pw.flush();

        final String full = sw.toString();

        int i = 0;

        while (i < full.length())
        {
            final int eol = full.indexOf('\n', i);

            if (eol < 0)
            {
                out.append(STACK_INDENT).append(full, i, full.length());

                break;
            }

            // The line content, including the '\n', with a
            // 4-space prefix on the content only. The '\n'
            // itself stays where it is so the next iteration
            // starts on a fresh line.
            out.append(STACK_INDENT)
                .append(full, i, eol)
                .append('\n');

            i = eol + 1;
        }
    }
}
